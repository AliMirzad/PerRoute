// Popup: connect, pick a server, and decide where the site in the current tab goes.
// All data from the helper (server names, messages) is untrusted and is only ever written with
// textContent, never as HTML.
import type { ImportResult, NativeEvent, RuleAction, ServerList, ServerSummary, Traffic } from '../../../protocol/types';
import type { AppState } from '../shared/app-state';
import { call, dismissNotices, endTrial, openDashboard, request, retryNative, subscribe } from '../shared/client';
import { $, clear, h, region, select, toast } from '../shared/dom';
import { DURATIONS, durationLabel, rate, until } from '../shared/format';
import { icon } from '../shared/icons';
import { outcomeFor, portOfUrl, siteRuleFor } from '../shared/rules-eval';
import { hostOfUrl, siteOf } from '../shared/site';
import { applyTheme } from '../shared/theme';
import { connectedFor, currentServerId, deriveView, primaryLabel, protocolLine, sortedServers } from '../shared/view';
import { captureVisibleTab, decodeQrFromBlob, looksLikeProxyConfig } from './qr';

let app: AppState | null = null;
let list: ServerList = { servers: [], subscriptions: [], selectedServerId: null };
let listLoaded = false;
let busy = false;
let tabUrl: string | undefined;
let tabHost: string | null = null;
/** "site" (youtube.com) or "host" (m.youtube.com): what the one-click rule covers. */
let scope: 'site' | 'host' = 'site';
let siteMinutes = 0;
let siteServer = '';
let traffic: Traffic | null = null;

const ready = () => app?.runtime.kind === 'ready';
const connected = () => app?.status?.state === 'connected';

function target(): string | null {
  if (!tabHost) return null;
  return scope === 'site' ? siteOf(tabHost) : tabHost;
}

const serverById = (id: string | null | undefined) => list.servers.find((s) => s.id === id);

function serverLabel(s: ServerSummary): string {
  const sc = list.scores?.[s.id];
  const star = list.favorites?.includes(s.id) ? '★ ' : '';
  const lat = sc?.latencyMs ? ` · ${sc.latencyMs} ms` : '';
  return `${star}${s.name}${lat}`;
}

async function refreshList() {
  if (!ready()) return;
  const r = await request('listServers', {});
  if (r.ok) {
    list = r.result as ServerList;
    listLoaded = true;
  }
  render();
}

async function refreshTraffic() {
  if (!ready() || !connected()) {
    if (traffic) {
      traffic = null;
      live.render();
    }
    return;
  }
  const r = await request('getTraffic', {});
  traffic = r.ok ? (r.result as Traffic) : null;
  live.render();
}

// ------------------------------------------------------------------ actions

async function run(fn: () => Promise<void>) {
  busy = true;
  render();
  try {
    await fn();
  } catch (e) {
    toast((e as Error).message, 'error');
  }
  busy = false;
  render();
}

const connect = (serverId: string) => run(async () => void (await call('connect', { serverId })));

const connectFastest = () =>
  run(async () => {
    toast('Testing servers…');
    const r = await call<{ serverId: string; ms: number }>('connectFastest', {});
    toast(`Connecting to ${serverById(r.serverId)?.name ?? 'the fastest server'} (${r.ms} ms)`);
    await refreshList();
  });

async function onPrimary() {
  if (!app) return;
  const v = deriveView(app);
  if (v.action === 'retry') return void retryNative();
  if (v.action === 'disconnect' || v.action === 'cancel') return run(async () => void (await call('disconnect', {})));
  if (v.action === 'connect') {
    if (app.settings?.fastestOnConnect && list.servers.length > 1) return connectFastest();
    const id = currentServerId(app, list.selectedServerId) ?? list.servers[0]?.id;
    if (id) await connect(id);
  }
}

async function chooseServer(id: string) {
  await request('selectServer', { id });
  list.selectedServerId = id;
  if (connected() && app?.status?.serverId !== id) await connect(id);
  else render();
}

async function setSite(action: RuleAction | null) {
  const t = target();
  if (!t) return;
  const r = await request('setSite', { host: t, action, serverId: action === 'proxy' && siteServer ? siteServer : null, minutes: action ? siteMinutes || null : null });
  if (!r.ok) return toast(r.error.message, 'error');
  const what = action === null ? 'follows your rules again' : action === 'proxy' ? 'uses the tunnel' : action === 'direct' ? 'opens directly' : 'is blocked';
  toast(`${t} ${what}${action && siteMinutes ? ` for ${durationLabel(siteMinutes)}` : ''}.`);
}

/** Reads a QR code on the visible page (activeTab is granted while the popup is open). */
async function scanQr() {
  try {
    const text = await decodeQrFromBlob(await captureVisibleTab());
    if (!text) return toast('No QR code found on this page.', 'error');
    if (!looksLikeProxyConfig(text)) return toast('This QR code does not contain a server configuration.', 'error');
    const r = await call<ImportResult>('importText', { text, source: 'qr' });
    toast(r.added || r.updated ? `${r.added} added, ${r.updated} updated.` : r.errors[0]?.message ?? 'Nothing new was found.', r.added || r.updated ? 'ok' : 'error');
    await refreshList();
  } catch (e) {
    toast(`Could not read the page: ${(e as Error).message}`, 'error');
  }
}

// ------------------------------------------------------------------ rendering

const notice = region(h('div'), () => {
  const n = app?.notices[0];
  if (!n) return [];
  return [
    h(
      'div',
      { class: `banner ${n.kind === 'routeDown' ? 'error' : 'info'}` },
      h('div', { class: 'grow small', text: n.message }),
      h('button', { class: 'link small', text: 'Dismiss', on: { click: () => void dismissNotices() } }),
    ),
  ];
}, () => app?.notices[0] ?? null);

const live = region(h('div', { class: 'live' }), () => {
  if (!traffic?.counting) return [];
  return [
    h('span', null, icon('down', 13), h('b', { text: rate(traffic.rate.proxyDown + traffic.rate.directDown) })),
    h('span', null, icon('up', 13), h('b', { text: rate(traffic.rate.proxyUp + traffic.rate.directUp) })),
  ];
}, () => [traffic?.counting, traffic?.rate]);

const conn = region(h('section', { class: 'card conn' }), () => {
  const v = deriveView(app!);
  const current = currentServerId(app!, list.selectedServerId);
  const st = app!.status?.state;
  const locked = busy || st === 'connecting' || st === 'disconnecting';
  const server = serverById(current);
  conn.el.dataset.tone = v.tone;

  const primary = h('button', { class: `btn big ${v.action === 'connect' ? 'primary' : ''}`, text: primaryLabel(v.action) });
  primary.disabled = busy || (v.action === 'connect' && list.servers.length === 0) || v.action === 'none';
  primary.onclick = () => void onPrimary();

  const trial = app!.trial;
  const detail = v.detail ?? (server ? `${protocolLine(server)} · ${server.address}` : null);
  return [
    h('div', { class: 'conn-head' }, h('div', { class: 'state', text: v.label }), v.tone === 'ok' && app!.status?.since ? h('span', { class: 'muted small', text: connectedFor(app!.status.since) ?? '' }) : null),
    detail ? h('div', { class: 'detail', text: detail }) : null,
    v.hint ? h('div', { class: 'detail', text: v.hint }) : null,
    trial
      ? h(
          'div',
          { class: 'trial' },
          icon('clock', 14),
          h('span', { class: 'grow', text: `Trying ${serverById(trial.serverId)?.name ?? 'a server'} · ${until(trial.until / 1000)} left` }),
          h('button', { class: 'link small', text: 'End now', on: { click: () => void endTrial() } }),
        )
      : null,
    !listLoaded
      ? h('select', { ariaLabel: 'Server', disabled: true }, h('option', { text: 'Loading servers…' }))
      : list.servers.length === 0
      ? h('button', { class: 'btn', on: { click: () => openDashboard('servers') } }, icon('plus', 14), 'Add servers')
      : h(
          'div',
          { class: 'server-pick' },
          select(
            sortedServers(list).map((s) => [s.id, serverLabel(s)]),
            current ?? '',
            (id) => id && void chooseServer(id),
            { ariaLabel: 'Server', disabled: locked },
          ),
          h('button', { class: 'btn icon', title: 'Connect to the fastest server', ariaLabel: 'Fastest server', disabled: locked || list.servers.length < 2, on: { click: () => void connectFastest() } }, icon('bolt')),
        ),
    primary,
    live.el,
  ];
}, () => [app?.runtime.kind, app?.status, app?.blocking, app?.proxyError, app?.trial, app?.settings?.fastestOnConnect, list, listLoaded, busy, Math.floor(Date.now() / 30000)]);

const site = region(h('section', { class: 'card stack site' }), () => {
  const t = target();
  if (!tabHost || !t) {
    return [h('div', { class: 'label', text: 'This site' }), h('p', { class: 'muted small', text: 'Open a website to choose how it connects.' })];
  }
  const rules = app?.rules;
  const now = Math.floor(Date.now() / 1000);
  const ev = rules ? outcomeFor(tabHost, rules.rules, rules.mode, rules.localNamesDirect, now, portOfUrl(tabUrl)) : null;
  const own = rules ? siteRuleFor(rules.rules, t) : null;
  const current: RuleAction | null = own && own.enabled ? own.action : null;
  const activeServer = app?.status?.serverId;
  const routeServer = ev?.rule?.serverId ? serverById(ev.rule.serverId)?.name : serverById(activeServer)?.name;
  const reason = !ev
    ? ''
    : ev.local
      ? 'Local address: stays on your network'
      : ev.rule
        ? `Rule "${ev.rule.name}"${ev.outcome === 'proxy' && routeServer ? ` · ${routeServer}` : ''}`
        : rules?.mode === 'selected'
          ? 'Only selected sites use the tunnel'
          : `Everything uses the tunnel${routeServer ? ` · ${routeServer}` : ''}`;
  const offline = !app?.browserProxied && !app?.blocking;
  const expires = own?.expiresAt && own.active ? `ends in ${until(own.expiresAt)}` : null;

  const scopeRow =
    siteOf(tabHost) !== tabHost
      ? h(
          'div',
          { class: 'site-scope', role: 'group', ariaLabel: 'What the choice covers' },
          ...(['site', 'host'] as const).map((s) =>
            h('button', {
              type: 'button',
              text: s === 'site' ? `All of ${siteOf(tabHost!)}` : `Only ${tabHost}`,
              attrs: { 'aria-pressed': String(scope === s) },
              on: {
                click: () => {
                  scope = s;
                  void chrome.storage.local.set({ siteScope: s }).catch(() => undefined);
                  site.render(true);
                },
              },
            }),
          ),
        )
      : null;

  const seg = h('div', { class: 'segmented full', role: 'group', ariaLabel: 'Where this site goes' });
  for (const [action, label] of [
    [null, 'Auto'],
    ['proxy', 'Tunnel'],
    ['direct', 'Direct'],
    ['block', 'Block'],
  ] as [RuleAction | null, string][]) {
    seg.append(h('button', { type: 'button', text: label, title: action === null ? 'Follow your rules and the routing mode' : undefined, attrs: { 'aria-pressed': String(current === action) }, on: { click: () => void setSite(action) } }));
  }

  return [
    h('div', { class: 'row' }, h('div', { class: 'label grow', text: 'This site' }), ev ? h('span', { class: `chip ${ev.outcome}`, text: ev.outcome === 'proxy' ? 'Tunnel' : ev.outcome === 'direct' ? 'Direct' : 'Blocked' }) : null),
    h(
      'div',
      null,
      h('div', { class: 'site-host ellipsis', title: t, text: t }),
      h('div', { class: 'muted small ellipsis', title: reason, text: [reason, expires].filter(Boolean).join(' · ') }),
      offline ? h('div', { class: 'small warn-text', text: 'Not connected: every site opens directly for now.' }) : null,
    ),
    scopeRow,
    seg,
    h(
      'div',
      { class: 'row' },
      select(
        [['0', 'Always'], ...DURATIONS.map((m) => [String(m), `For ${durationLabel(m)}`] as [string, string])],
        String(siteMinutes),
        (v) => (siteMinutes = Number(v)),
        { ariaLabel: 'For how long', class: 'grow' },
      ),
      list.servers.length > 1
        ? select([['', 'Main server'], ...sortedServers(list).map((s) => [s.id, s.name] as [string, string])], siteServer, (v) => (siteServer = v), { ariaLabel: 'Server for this site (Tunnel)', class: 'grow', title: 'Server used when you choose Tunnel' })
        : null,
    ),
  ];
}, () => [tabHost, scope, app?.rules, app?.browserProxied, app?.blocking, app?.status?.serverId, list.servers.length, list.servers.map((s) => s.name), Math.floor(Date.now() / 60000)]);

function footer(): HTMLElement {
  const item = (name: string, label: string, onClick: () => void, title?: string) => h('button', { type: 'button', title: title ?? label, on: { click: onClick } }, icon(name, 18), label);
  return h(
    'nav',
    { class: 'footer' },
    item('servers', 'Servers', () => openDashboard('servers')),
    item('rules', 'Rules', () => openDashboard('rules')),
    item('qr', 'Scan QR', () => void scanQr(), 'Add servers from a QR code on this page'),
    item('doctor', 'Doctor', () => openDashboard('doctor')),
    item('settings', 'Settings', () => openDashboard('settings')),
  );
}
const foot = footer();

const runtimeCard = region(h('section', { class: 'card stack' }), () => {
  const v = deriveView(app!);
  return [
    h('h2', { text: v.label }),
    v.detail ? h('p', { class: 'muted small', text: v.detail }) : null,
    v.hint ? h('p', { class: 'small', text: v.hint }) : null,
    v.action === 'retry' ? h('button', { class: 'btn primary', text: 'Retry', on: { click: () => void retryNative() } }) : null,
  ];
}, () => app?.runtime);

let shown: 'runtime' | 'main' | null = null;
function render() {
  if (!app) return;
  const v = deriveView(app);
  $('status-dot').className = `dot ${v.tone}`;
  $('status-label').textContent = v.label;
  const main = $('main');
  const want = ready() ? 'main' : 'runtime';
  if (want !== shown) {
    shown = want;
    if (want === 'runtime') clear(main, runtimeCard.el);
    else clear(main, notice.el, conn.el, site.el, foot);
  }
  if (want === 'runtime') return runtimeCard.render();
  notice.render();
  conn.render();
  site.render();
}

// ------------------------------------------------------------------ start

async function loadTab() {
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    tabUrl = tab?.url;
    tabHost = hostOfUrl(tab?.url);
  } catch {
    tabHost = null;
  }
  try {
    const { siteScope } = await chrome.storage.local.get('siteScope');
    if (siteScope === 'host' || siteScope === 'site') scope = siteScope;
  } catch {
    /* preference only */
  }
}

async function main() {
  await applyTheme();
  $('open-dashboard').onclick = () => openDashboard();
  await loadTab();
  let wasReady = false;
  subscribe(
    (s) => {
      app = s;
      const isReady = s.runtime.kind === 'ready';
      if (isReady && !wasReady) {
        void refreshList();
        void refreshTraffic();
      }
      wasReady = isReady;
      render();
    },
    (e: NativeEvent) => {
      if (e.event === 'changed') void refreshList();
    },
  );
  const timer = setInterval(() => {
    void refreshTraffic();
    render();
  }, 3000);
  window.addEventListener('pagehide', () => clearInterval(timer));
}

void main();
