// The service worker's brain. All browser APIs are injected (see chrome-adapters.ts), so the
// logic here is unit-tested with fakes (tests/controller.test.ts).
//
// Invariants:
//  * The browser proxy points at the tunnel ONLY while the helper reports `connected` with a
//    proxy port.
//  * In every other state the proxy is cleared, unless the kill switch is on and the user wants
//    to be connected: then the sites that should use the tunnel are pointed at a closed local
//    port, so they fail instead of loading without the tunnel. Disconnect always releases it.
//  * UI requests are forwarded only if the command is in UI_COMMANDS.
//  * The UI never says "connected" while our proxy setting is not in effect: if another
//    extension or a policy takes over the browser proxy, the tunnel is disconnected and the
//    reason is shown (proxyControlChanged).

import {
  HOST_NAME,
  LONG_COMMANDS,
  UI_COMMANDS,
  type ApiError,
  type CommandName,
  type HelloResult,
  type NativeEvent,
  type NativeResponse,
  type NativeStatus,
  type Notice,
  type RulesState,
  type Settings,
} from '../../../protocol/types';
import { EXTENSION_PROTOCOL_VERSION, type AppState, type RuntimeState, type Trial } from '../shared/app-state';
import { killSwitchPac } from '../shared/rules-eval';

export interface PortLike {
  postMessage(msg: unknown): void;
  disconnect(): void;
  onMessage: { addListener(cb: (msg: unknown) => void): void };
  onDisconnect: { addListener(cb: () => void): void };
}

/** What the kill switch does: a PAC script that sends every destination that would use the tunnel to a closed port. */
export type BlockPlan = { mode: 'pac'; script: string };

export interface ProxyControl {
  /** Route the browser through 127.0.0.1:port. Resolves to an error message if we could not take control. */
  set(port: number): Promise<string | null>;
  /** Kill switch: send tunnel traffic to a closed local port. Resolves to an error message on failure. */
  block(plan: BlockPlan): Promise<string | null>;
  /** Remove our proxy setting (browser goes direct). */
  clear(): Promise<void>;
  /** Null if our setting is the one in effect, otherwise why not. */
  controlProblem(): Promise<string | null>;
}

export interface WebRtcControl {
  /** Apply (true) or restore (false) the WebRTC leak protection; no-op if not permitted. */
  apply(protect: boolean): Promise<void>;
  /** Null if the leak protection is in effect, otherwise why not (e.g. another extension overrode it). */
  problem(): Promise<string | null>;
}

/** Small persistent values: `session` survives service-worker restarts, `local` survives browser restarts. */
export interface KeyValue {
  get(key: string): Promise<unknown>;
  set(key: string, value: unknown): Promise<void>;
}

export interface Deps {
  connectNative(host: string): PortLike;
  /** Error text of the last native disconnect (chrome.runtime.lastError). */
  lastError(): string | undefined;
  proxy: ProxyControl;
  webrtc: WebRtcControl;
  broadcast(state: AppState): void;
  /** Helper events for open extension pages (progress of tests, notices, data changes). */
  broadcastEvent?(event: NativeEvent): void;
  notify?(notice: Notice): void;
  session?: KeyValue;
  local?: KeyValue;
  extensionVersion: string;
  setTimer(cb: () => void, ms: number): unknown;
  clearTimer(t: unknown): void;
  requestTimeoutMs?: number;
  now?(): number;
  /** A wake-up that survives service-worker restarts (chrome.alarms); `at` in Unix ms. */
  schedule?(name: string, at: number): void;
  unschedule?(name: string): void;
}

export const TRIAL_ALARM = 'trial';

type Pending = { resolve: (r: NativeResponse) => void; timer: unknown };

const RETRY_DELAYS = [1000, 3000, 10000, 30000];
/** The kill switch's target: a loopback port nothing listens on. */
export const CLOSED_PORT = 1;
const MAX_NOTICES = 10;
const RULE_COMMANDS = new Set(['addRule', 'updateRule', 'deleteRule', 'moveRule', 'setRuleEnabled', 'setSite', 'replaceRules', 'applyScenario', 'clearScenario', 'applyProfile', 'restoreBackup', 'undoRestore', 'resetAll']);

export function classifyDisconnect(err: string | undefined): RuntimeState {
  const e = err ?? '';
  if (/not found/i.test(e)) {
    return { kind: 'missing', message: 'The PerRoute browser runtime is not installed.' };
  }
  if (/forbidden/i.test(e)) {
    return { kind: 'forbidden', message: 'The runtime is installed for a different extension ID. Reinstall PerRoute.' };
  }
  return { kind: 'crashed', message: 'The browser runtime stopped unexpectedly.', retryInMs: null };
}

export class Controller {
  state: AppState;
  private port: PortLike | null = null;
  private nextId = 1;
  private pending = new Map<number, Pending>();
  private retryIndex = 0;
  private retryTimer: unknown = null;
  private proxyChain: Promise<void> = Promise.resolve();
  private generation = 0;
  /** Credentials of the browser proxy for the current connection (memory only, never broadcast). */
  private proxyAuth: { port: number; username: string; password: string } | null = null;
  /** The user wants to be connected (pressed Connect and not Disconnect). */
  private wantConnected = false;
  private lastServerId: string | null = null;
  private firstReady = true;

  constructor(private deps: Deps) {
    this.state = {
      runtime: { kind: 'starting' },
      status: null,
      proxyError: null,
      browserProxied: false,
      blocking: false,
      settings: null,
      rules: null,
      notices: [],
      trial: null,
      extensionVersion: deps.extensionVersion,
    };
  }

  /** Called on every service-worker start. */
  async start(): Promise<void> {
    this.wantConnected = (await this.deps.session?.get('wantConnected').catch(() => false)) === true;
    const last = await this.deps.local?.get('lastServerId').catch(() => null);
    this.lastServerId = typeof last === 'string' ? last : null;
    const trial = (await this.deps.local?.get('trial').catch(() => null)) as Trial | null | undefined;
    if (trial && typeof trial.serverId === 'string' && typeof trial.until === 'number') this.state.trial = trial;
    // A fresh service worker never owns a live tunnel (the helper dies with the old port), so any
    // proxy setting left from before is stale. With the kill switch, tunnel sites stay blocked
    // until the tunnel is back.
    await this.enqueueProxy(async () => {
      await this.deps.proxy.clear();
      this.state.browserProxied = false;
      this.state.blocking = false;
    });
    this.connect();
  }

  private now(): number {
    return this.deps.now?.() ?? Date.now();
  }

  private emit() {
    this.deps.broadcast(structuredClone(this.state));
  }

  private setRuntime(r: RuntimeState) {
    this.state.runtime = r;
    this.emit();
  }

  private setWant(v: boolean) {
    this.wantConnected = v;
    void this.deps.session?.set('wantConnected', v).catch(() => undefined);
  }

  connect(): void {
    if (this.port) return;
    if (this.retryTimer) {
      this.deps.clearTimer(this.retryTimer);
      this.retryTimer = null;
    }
    const gen = ++this.generation;
    this.setRuntime({ kind: 'starting' });
    let port: PortLike;
    try {
      port = this.deps.connectNative(HOST_NAME);
    } catch (e) {
      this.onDisconnect(gen, String(e));
      return;
    }
    this.port = port;
    port.onMessage.addListener((m) => this.onMessage(m));
    port.onDisconnect.addListener(() => this.onDisconnect(gen, this.deps.lastError()));
    this.send('hello', { protocolVersion: EXTENSION_PROTOCOL_VERSION, extensionVersion: this.deps.extensionVersion }).then((r) => {
      if (gen !== this.generation) return;
      if (r.ok) {
        this.retryIndex = 0;
        this.setRuntime({ kind: 'ready', hello: r.result as HelloResult });
        void this.afterReady(gen);
      } else if (r.error.code === 'INCOMPATIBLE_VERSION') {
        this.setRuntime({ kind: 'incompatible', message: r.error.message });
        this.dropPort();
      }
    });
  }

  private async afterReady(gen: number) {
    const s = await this.send('getStatus', {});
    if (gen !== this.generation) return;
    if (s.ok) this.onStatus(s.result as NativeStatus);
    await Promise.all([this.loadSettings(), this.loadRules()]);
    if (gen !== this.generation) return;
    const startup = this.firstReady;
    this.firstReady = false;
    const st = this.state.status?.state;
    const idle = !st || st === 'disconnected' || st === 'error';
    // Reconnect after the runtime restarted while connected, or on browser start when enabled.
    const auto = this.wantConnected || (startup && this.state.settings?.autoConnect === true);
    if (idle && auto && this.lastServerId) {
      this.setWant(true);
      void this.send('connect', { serverId: this.lastServerId }).then((r) => r.ok && this.onStatus(r.result as NativeStatus));
    }
    if (this.state.trial && this.state.trial.until <= this.now()) void this.endTrial();
  }

  private async loadSettings() {
    const r = await this.send('getSettings', {});
    if (r.ok) {
      this.state.settings = r.result as Settings;
      this.emit();
    }
  }

  private async loadRules() {
    const r = await this.send('getRules', {});
    if (r.ok) {
      this.state.rules = r.result as RulesState;
      this.emit();
      // In "selected sites" mode the kill switch blocks exactly the tunnel sites: keep it current.
      if (this.state.blocking) void this.enqueueProxy(() => this.applyBlock());
    }
  }

  /** User asked to retry (e.g. popup opened while the runtime was missing). */
  retry(): void {
    this.retryIndex = 0;
    this.connect();
  }

  private dropPort() {
    const p = this.port;
    this.port = null;
    this.generation++;
    try {
      p?.disconnect();
    } catch {
      /* already gone */
    }
    this.failPending({ code: 'INTERNAL', message: 'The browser runtime disconnected' });
  }

  private failPending(error: ApiError) {
    for (const [id, p] of this.pending) {
      this.deps.clearTimer(p.timer);
      p.resolve({ id, ok: false, error });
    }
    this.pending.clear();
  }

  private onDisconnect(gen: number, err: string | undefined) {
    if (gen !== this.generation) return;
    this.proxyAuth = null;
    this.generation++; // late responses from the dead port must not change state
    this.port = null;
    this.failPending({ code: 'INTERNAL', message: 'The browser runtime is not available' });
    // The helper (and Xray) are gone: never leave the browser pointing at a dead proxy. With the
    // kill switch, tunnel sites are blocked instead of falling back to a direct connection.
    void this.enqueueProxy(async () => {
      this.state.browserProxied = false;
      await this.deps.webrtc.apply(false);
      if (this.killSwitchHolds()) {
        await this.applyBlock();
      } else {
        await this.deps.proxy.clear();
        this.state.blocking = false;
      }
      this.emit();
    });
    this.state.status = null;
    let r = classifyDisconnect(err);
    if (r.kind === 'crashed') {
      const delay = RETRY_DELAYS[Math.min(this.retryIndex, RETRY_DELAYS.length - 1)];
      this.retryIndex++;
      r = { ...r, retryInMs: delay };
      this.retryTimer = this.deps.setTimer(() => {
        this.retryTimer = null;
        this.connect();
      }, delay);
    }
    this.setRuntime(r);
  }

  private onMessage(raw: unknown) {
    const m = raw as NativeResponse & NativeEvent;
    if (typeof m !== 'object' || m === null) return;
    if ('id' in m && typeof m.id === 'number') {
      const p = this.pending.get(m.id);
      if (p) {
        this.pending.delete(m.id);
        this.deps.clearTimer(p.timer);
        p.resolve(m as NativeResponse);
      }
      return;
    }
    if (!('event' in m)) return;
    const ev = m as NativeEvent;
    switch (ev.event) {
      case 'status':
        this.onStatus(ev.status);
        return;
      case 'notice':
        this.state.notices = [{ ...ev.notice, at: this.now() }, ...this.state.notices].slice(0, MAX_NOTICES);
        this.deps.notify?.(ev.notice);
        this.emit();
        break;
      case 'changed':
        void this.loadRules();
        void this.loadSettings();
        break;
    }
    this.deps.broadcastEvent?.(ev);
  }

  private send(cmd: string, args: unknown, timeoutMs = this.deps.requestTimeoutMs ?? 60000): Promise<NativeResponse> {
    return new Promise((resolve) => {
      if (!this.port) {
        resolve({ id: 0, ok: false, error: { code: 'INTERNAL', message: 'The browser runtime is not available' } });
        return;
      }
      const id = this.nextId++;
      const timer = this.deps.setTimer(() => {
        this.pending.delete(id);
        resolve({ id, ok: false, error: { code: 'INTERNAL', message: 'The browser runtime did not respond in time' } });
      }, timeoutMs);
      this.pending.set(id, { resolve, timer });
      try {
        this.port.postMessage({ id, cmd, args });
      } catch (e) {
        this.pending.delete(id);
        this.deps.clearTimer(timer);
        resolve({ id, ok: false, error: { code: 'INTERNAL', message: String(e) } });
      }
    });
  }

  /** Entry point for UI requests. Enforces the command allow-list. */
  async request(cmd: string, args: unknown): Promise<NativeResponse> {
    if (!(UI_COMMANDS as readonly string[]).includes(cmd)) {
      return { id: 0, ok: false, error: { code: 'INVALID_REQUEST', message: 'Unknown command' } };
    }
    if (typeof args !== 'object' || args === null || Array.isArray(args)) {
      return { id: 0, ok: false, error: { code: 'INVALID_REQUEST', message: 'Invalid arguments' } };
    }
    if (this.state.runtime.kind !== 'ready') {
      return { id: 0, ok: false, error: { code: 'INTERNAL', message: runtimeMessage(this.state.runtime) } };
    }
    if (cmd === 'connect' || cmd === 'connectFastest') this.setWant(true);
    // Choosing a server or disconnecting ends a trial: the user's own choice wins.
    if ((cmd === 'connect' || cmd === 'connectFastest' || cmd === 'disconnect') && this.state.trial) this.setTrial(null);
    if (cmd === 'disconnect') {
      this.setWant(false);
      // Disconnect always releases the kill switch, even if the helper is unreachable.
      if (this.state.blocking) {
        void this.enqueueProxy(async () => {
          await this.deps.proxy.clear();
          this.state.blocking = false;
          this.emit();
        });
      }
    }
    const timeout = Math.max(this.deps.requestTimeoutMs ?? 60000, LONG_COMMANDS[cmd as CommandName] ?? 0);
    const r = await this.send(cmd as CommandName, args, timeout);
    if (r.ok && (cmd === 'connect' || cmd === 'disconnect' || cmd === 'getStatus')) {
      this.onStatus(r.result as NativeStatus);
    }
    if (!r.ok && cmd === 'connectFastest') this.setWant(this.state.status?.state === 'connected');
    if (r.ok && cmd === 'setSettings') {
      const before = this.state.settings;
      const { reconnectRequired: _, ...settings } = r.result as Settings & { reconnectRequired?: boolean };
      this.state.settings = settings as Settings;
      this.emit();
      void this.enqueueProxy(() => this.settingsChanged(before));
    }
    if (r.ok && RULE_COMMANDS.has(cmd)) void this.loadRules();
    if (r.ok && (cmd === 'applyScenario' || cmd === 'restoreBackup' || cmd === 'undoRestore' || cmd === 'resetAll' || cmd === 'applyProfile')) void this.loadSettings();
    return r;
  }

  /** Applies protection settings at once instead of at the next connection. */
  private async settingsChanged(before: Settings | null) {
    const s = this.state.settings;
    if (!s) return;
    if (this.state.blocking && !s.killSwitch) {
      await this.deps.proxy.clear();
      this.state.blocking = false;
    } else if (!this.state.blocking && !this.state.browserProxied && this.killSwitchHolds()) {
      // Turned on while the tunnel is down (an error, or reconnecting): hold tunnel sites now.
      const st = this.state.status?.state;
      if (st !== 'connected' && st !== 'disconnecting') await this.applyBlock();
    }
    if (this.state.browserProxied && before && before.webrtcProtection !== s.webrtcProtection) {
      await this.deps.webrtc.apply(s.webrtcProtection);
      const problem = s.webrtcProtection ? await this.deps.webrtc.problem() : null;
      if (problem) this.state.proxyError = problem;
    }
    this.emit();
  }

  // ---------------------------------------------------------------- trying a server for a while

  private setTrial(t: Trial | null) {
    this.state.trial = t;
    void this.deps.local?.set('trial', t).catch(() => undefined);
    if (t) this.deps.schedule?.(TRIAL_ALARM, t.until);
    else this.deps.unschedule?.(TRIAL_ALARM);
    this.emit();
  }

  /** Connects to `serverId` for `minutes`, then back to the server used before. */
  async tryServer(serverId: string, minutes: number): Promise<NativeResponse> {
    if (this.state.runtime.kind !== 'ready') return { id: 0, ok: false, error: { code: 'INTERNAL', message: runtimeMessage(this.state.runtime) } };
    if (typeof serverId !== 'string' || !Number.isInteger(minutes) || minutes < 5 || minutes > 24 * 60) {
      return { id: 0, ok: false, error: { code: 'INVALID_REQUEST', message: 'Choose between 5 minutes and 24 hours.' } };
    }
    const st = this.state.status;
    const previous = this.state.trial?.previousId ?? (st?.state === 'connected' ? (st.serverId ?? null) : this.lastServerId);
    this.setWant(true);
    const r = await this.send('connect', { serverId });
    if (!r.ok) return r;
    this.onStatus(r.result as NativeStatus);
    this.setTrial({ serverId, previousId: previous && previous !== serverId ? previous : null, until: this.now() + minutes * 60_000 });
    return r;
  }

  /** Ends a trial: back to the previous server if the trial server is still in use. */
  async endTrial(): Promise<void> {
    const t = this.state.trial;
    if (!t) return;
    this.setTrial(null);
    const st = this.state.status;
    const onTrial = st && (st.state === 'connected' || st.state === 'connecting') && st.serverId === t.serverId;
    if (onTrial && t.previousId && this.state.runtime.kind === 'ready') {
      const r = await this.send('connect', { serverId: t.previousId });
      if (r.ok) this.onStatus(r.result as NativeStatus);
      this.deps.notify?.({ kind: 'trialEnded', message: r.ok ? 'The trial ended: back on your previous server.' : `The trial ended, but the previous server could not be used: ${r.error.message}` });
    }
  }

  dismissNotices(): void {
    this.state.notices = [];
    this.emit();
  }

  private enqueueProxy(op: () => Promise<void>): Promise<void> {
    this.proxyChain = this.proxyChain.then(op).catch(() => undefined);
    return this.proxyChain;
  }

  private killSwitchHolds(): boolean {
    return this.wantConnected && this.state.settings?.killSwitch === true;
  }

  private blockPlan(): BlockPlan {
    // Rules unknown (the runtime went away before they loaded): block everything but local addresses.
    const rules = this.state.rules;
    const script = rules ? killSwitchPac(rules.rules, rules.mode, rules.localNamesDirect, Math.floor(this.now() / 1000), CLOSED_PORT) : killSwitchPac([], 'all', true, 0, CLOSED_PORT);
    return { mode: 'pac', script };
  }

  private async applyBlock() {
    const err = await this.deps.proxy.block(this.blockPlan());
    this.state.blocking = !err;
    if (err) {
      this.state.proxyError = err;
      await this.deps.proxy.clear();
    }
  }

  /** Mirrors helper state into browser proxy settings. */
  onStatus(status: NativeStatus): void {
    if (status.state === 'connected' && status.proxy?.username && status.proxy.password) {
      this.proxyAuth = { port: status.proxy.port, username: status.proxy.username, password: status.proxy.password };
    } else if (!(status.state === 'connecting' && status.phase === 'restarting')) {
      this.proxyAuth = null;
    }
    // The UI gets the status without the credentials.
    this.state.status = status.proxy ? { ...status, proxy: { scheme: status.proxy.scheme, host: status.proxy.host, port: status.proxy.port } } : status;
    if (status.state === 'connected' && status.serverId && status.serverId !== this.lastServerId) {
      this.lastServerId = status.serverId;
      void this.deps.local?.set('lastServerId', status.serverId).catch(() => undefined);
    }
    if (status.state === 'connected') this.setWant(true);
    // A failover moved away from the server on trial: the trial is over.
    if (status.state === 'connected' && this.state.trial && status.serverId !== this.state.trial.serverId) this.setTrial(null);
    this.emit();
    const connected = status.state === 'connected' && status.proxy;
    const keepDuringRestart = status.state === 'connecting' && status.phase === 'restarting' && this.state.browserProxied;
    void this.enqueueProxy(async () => {
      if (connected && status.proxy) {
        const err = await this.deps.proxy.set(status.proxy.port);
        if (err) {
          this.state.proxyError = err;
          this.state.browserProxied = false;
          this.state.blocking = false;
          await this.deps.proxy.clear();
          this.emit();
          this.setWant(false);
          void this.send('disconnect', {});
          return;
        }
        this.state.proxyError = null;
        this.state.browserProxied = true;
        this.state.blocking = false;
        const protect = this.state.settings?.webrtcProtection !== false;
        await this.deps.webrtc.apply(protect);
        // Fail closed: WebRTC protection is on (the default) but not in effect, e.g. another
        // extension controls the setting. Browsing would leak the real IP address over WebRTC.
        const rtcProblem = protect ? await this.deps.webrtc.problem() : null;
        if (rtcProblem) {
          this.state.proxyError = `${rtcProblem} The tunnel was disconnected.`;
          this.state.browserProxied = false;
          await this.deps.proxy.clear();
          await this.deps.webrtc.apply(false);
          this.emit();
          this.setWant(false);
          void this.send('disconnect', {});
          return;
        }
      } else if (!keepDuringRestart) {
        const wasProxied = this.state.browserProxied;
        this.state.browserProxied = false;
        if (status.state === 'disconnected' && !this.wantConnected) {
          if (wasProxied || this.state.blocking) {
            await this.deps.proxy.clear();
            await this.deps.webrtc.apply(false);
          }
          this.state.blocking = false;
          this.state.proxyError = null;
        } else if (this.killSwitchHolds() && status.state !== 'disconnecting') {
          if (wasProxied) await this.deps.webrtc.apply(false);
          await this.applyBlock();
        } else if (wasProxied || this.state.blocking || status.state !== 'connecting') {
          await this.deps.proxy.clear();
          await this.deps.webrtc.apply(false);
          this.state.blocking = false;
        }
        if (status.state === 'disconnected' && !this.wantConnected) this.state.proxyError = null;
      }
      this.emit();
    });
  }

  /**
   * chrome.proxy.settings changed (by us, another extension, or policy). If the tunnel is up but
   * our setting is no longer the one in effect, browser traffic is not going through the tunnel:
   * disconnect and say why, instead of continuing to show "connected".
   */
  proxyControlChanged(): void {
    void this.enqueueProxy(async () => {
      if (!this.state.browserProxied) return;
      // The proxy setting, and (when enabled) the WebRTC leak protection, must both stay ours.
      const protect = this.state.settings?.webrtcProtection !== false;
      const problem = (await this.deps.proxy.controlProblem()) ?? (protect ? await this.deps.webrtc.problem() : null);
      if (!problem) return;
      this.state.browserProxied = false;
      this.state.proxyError = `${problem} The tunnel was disconnected.`;
      await this.deps.proxy.clear();
      await this.deps.webrtc.apply(false);
      this.emit();
      this.setWant(false);
      void this.send('disconnect', {});
    });
  }

  /**
   * Credentials for a proxy authentication challenge, or null. Only our own tunnel inbound
   * (127.0.0.1 and exactly the current port) is ever answered, and only while our proxy setting is
   * in effect, so the credentials cannot be sent to any other proxy.
   */
  proxyCredentials(challenger: { host: string; port: number }): { username: string; password: string } | null {
    const a = this.proxyAuth;
    if (!a || !this.state.browserProxied) return null;
    if (challenger.host !== '127.0.0.1' || challenger.port !== a.port) return null;
    return { username: a.username, password: a.password };
  }

  /** For tests. */
  idle(): Promise<void> {
    return this.proxyChain;
  }
}

export function runtimeMessage(r: RuntimeState): string {
  switch (r.kind) {
    case 'ready':
      return '';
    case 'starting':
      return 'Starting the browser runtime…';
    default:
      return r.message;
  }
}
