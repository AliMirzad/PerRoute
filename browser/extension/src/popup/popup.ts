// Popup: connect, pick a server, and decide where the site in the current tab goes.
// All data from the helper (server names, messages) is untrusted and is only ever written with
// textContent, never as HTML.
import type { Evaluation, NativeEvent, RuleAction, ServerList, ServerSummary, Traffic } from '../../../protocol/types';
import type { AppState } from '../shared/app-state';
import { call, dismissNotices, openDashboard, request, retryNative, subscribe } from '../shared/client';
import { $, clear, h, toast } from '../shared/dom';
import { DURATIONS, durationLabel, rate, until } from '../shared/format';
import { icon } from '../shared/icons';
import { hostOfUrl, siteOf } from '../shared/site';
import { applyTheme } from '../shared/theme';
import { currentServerId, deriveView, protocolLine } from '../shared/view';

let app: AppState | null = null;
let list: ServerList = { servers: [], subscriptions: [], selectedServerId: null };
let busy = false;
let tabHost: string | null = null;
/** "site" (youtube.com) or "host" (m.youtube.com): what the one-click rule covers. */
let scope: 'site' | 'host' = 'site';
let evaluation: Evaluation | null = null;
let siteMinutes = 0;
let siteServer = '';
let traffic: Traffic | null = null;
let trafficTimer: ReturnType<typeof setInterval> | null = null;

const ready = () => app?.runtime.kind === 'ready';
const connected = () => app?.status?.state === 'connected';

function target(): string | null {
  if (!tabHost) return null;
  return scope === 'site' ? siteOf(tabHost) : tabHost;
}

async function refreshList() {
  if (!ready()) return;
  const r = await request('listServers', {});
  if (r.ok) list = r.result as ServerList;
  render();
}

async function refreshEvaluation() {
  const t = target();
  if (!ready() || !t) {
    evaluation = null;
    render();
    return;
  }
  const r = await request('evaluate', { host: t });
  evaluation = r.ok ? (r.result as Evaluation) : null;
  render();
}

async function refreshTraffic() {
  if (!ready() || !connected()) {
    traffic = null;
    return;
  }
  const r = await request('getTraffic', {});
  traffic = r.ok ? (r.result as Traffic) : null;
  renderLive();
}

function sortedServers(): ServerSummary[] {
  const fav = new Set(list.favorites ?? []);
  return [...list.servers].sort((a, b) => Number(fav.has(b.id)) - Number(fav.has(a.id)));
}

function serverLabel(s: ServerSummary): string {
  const sc = list.scores?.[s.id];
  const star = list.favorites?.includes(s.id) ? '★ ' : '';
  const lat = sc?.latencyMs ? ` · ${sc.latencyMs} ms` : '';
  return `${star}${s.name}${lat}`;
}

// ------------------------------------------------------------------ actions

async function connect(serverId: string) {
  busy = true;
  render();
  const r = await request('connect', { serverId });
  busy = false;
  if (!r.ok) toast(r.error.message, 'error');
  render();
}

async function connectFastest() {
  busy = true;
  render();
  toast('Testing servers…');
  try {
    const r = await call<{ serverId: string; ms: number }>('connectFastest', {});
    const s = list.servers.find((x) => x.id === r.serverId);
    toast(`Connecting to ${s?.name ?? 'the fastest server'} (${r.ms} ms)`);
  } catch (e) {
    toast((e as Error).message, 'error');
  }
  busy = false;
  await refreshList();
}

async function onPrimary() {
  if (!app) return;
  const v = deriveView(app);
  if (v.action === 'retry') {
    await retryNative();
    return;
  }
  if (v.action === 'disconnect' || v.action === 'cancel' || app.blocking) {
    busy = true;
    render();
    const r = await request('disconnect', {});
    busy = false;
    if (!r.ok) toast(r.error.message, 'error');
    render();
    return;
  }
  if (v.action === 'connect') {
    if (app.settings?.fastestOnConnect && list.servers.length > 1) {
      await connectFastest();
      return;
    }
    const id = currentServerId(app, list.selectedServerId) ?? list.servers[0]?.id;
    if (id) await connect(id);
  }
}

async function chooseServer(id: string) {
  await request('selectServer', { id });
  list.selectedServerId = id;
  if (connected() && app?.status?.serverId !== id) await connect(id);
  render();
}

async function setSite(action: RuleAction | null) {
  const t = target();
  if (!t) return;
  const r = await request('setSite', { host: t, action, serverId: action === 'proxy' && siteServer ? siteServer : null, minutes: action ? siteMinutes || null : null });
  if (!r.ok) {
    toast(r.error.message, 'error');
    return;
  }
  const what = action === null ? 'follows your rules again' : action === 'proxy' ? 'uses the tunnel' : action === 'direct' ? 'opens directly' : 'is blocked';
  toast(`${t} ${what}${action && siteMinutes ? ` for ${durationLabel(siteMinutes)}` : ''}.`);
  await refreshEvaluation();
}

// ------------------------------------------------------------------ rendering

function renderLive() {
  const el = document.getElementById('live');
  if (!el) return;
  if (!traffic?.counting) {
    el.hidden = true;
    return;
  }
  el.hidden = false;
  clear(el, h('span', null, '↓ ', h('b', { text: rate(traffic.rate.proxyDown + traffic.rate.directDown) })), h('span', null, '↑ ', h('b', { text: rate(traffic.rate.proxyUp + traffic.rate.directUp) })));
}

function noticeBanner(): HTMLElement | null {
  const n = app?.notices[0];
  if (!n) return null;
  return h(
    'div',
    { class: `banner ${n.kind === 'routeDown' ? 'error' : ''}` },
    h('div', { class: 'grow small', text: n.message }),
    h('button', { class: 'link small', text: 'Dismiss', on: { click: () => void dismissNotices() } }),
  );
}

function runtimeCard(): HTMLElement | null {
  if (!app || ready()) return null;
  const v = deriveView(app);
  return h(
    'section',
    { class: 'card stack' },
    h('h2', { text: v.label }),
    v.detail ? h('p', { class: 'muted small', text: v.detail }) : null,
    v.hint ? h('p', { class: 'small', text: v.hint }) : null,
    v.action === 'retry' ? h('button', { class: 'btn primary', text: 'Retry', on: { click: () => void retryNative() } }) : null,
  );
}

function connectionCard(): HTMLElement {
  const v = deriveView(app!);
  const current = currentServerId(app!, list.selectedServerId);
  const st = app!.status?.state;
  const locked = busy || st === 'connecting' || st === 'disconnecting';
  const server = list.servers.find((s) => s.id === current);
  const sel = h('select', { ariaLabel: 'Server', disabled: locked || list.servers.length === 0 });
  for (const s of sortedServers()) sel.append(h('option', { value: s.id, text: serverLabel(s) }));
  if (list.servers.length === 0) sel.append(h('option', { value: '', text: 'No servers yet' }));
  sel.value = current ?? '';
  sel.onchange = () => sel.value && void chooseServer(sel.value);

  const primary = h('button', { class: `btn big ${v.action === 'connect' ? 'primary' : ''}` });
  primary.disabled = busy || (v.action === 'connect' && list.servers.length === 0) || v.action === 'none';
  primary.textContent = app!.blocking ? 'Disconnect' : v.action === 'connect' ? 'Connect' : v.action === 'disconnect' ? 'Disconnect' : v.action === 'cancel' ? 'Cancel' : v.action === 'retry' ? 'Retry' : 'Please wait…';
  primary.onclick = () => void onPrimary();

  const detail = app!.blocking
    ? 'Kill switch: sites that use the tunnel stay offline until it is back. Disconnect to browse without it.'
    : (v.detail ?? (server ? `${protocolLine(server)} · ${server.address}` : null));

  return h(
    'section',
    { class: 'card conn', data: { tone: app!.blocking ? 'error' : v.tone } },
    h('div', null, h('div', { class: 'state', text: app!.blocking ? 'Tunnel down' : v.label }), detail ? h('div', { class: 'detail', text: detail }) : null),
    list.servers.length === 0
      ? h('button', { class: 'btn', text: 'Add servers', on: { click: () => openDashboard('servers') } })
      : h(
          'div',
          { class: 'server-pick' },
          sel,
          h('button', { class: 'btn icon', title: 'Connect to the fastest server', ariaLabel: 'Fastest server', disabled: locked || list.servers.length < 2, on: { click: () => void connectFastest() } }, icon('bolt')),
        ),
    primary,
    h('div', { id: 'live', class: 'live', hidden: true }),
  );
}

function siteCard(): HTMLElement | null {
  if (!ready()) return null;
  const t = target();
  if (!tabHost || !t) {
    return h('section', { class: 'card' }, h('div', { class: 'label', text: 'This site' }), h('p', { class: 'muted small', text: 'Open a website to choose how it connects.' }));
  }
  const ev = evaluation;
  const own = ev?.siteRule ?? null;
  const current: RuleAction | null = own ? own.action : null;
  const scopeRow =
    siteOf(tabHost) !== tabHost
      ? h(
          'div',
          { class: 'site-scope' },
          ...(['site', 'host'] as const).map((s) =>
            h('button', {
              text: s === 'site' ? `All of ${siteOf(tabHost!)}` : `Only ${tabHost}`,
              attrs: { 'aria-pressed': String(scope === s) },
              on: {
                click: () => {
                  scope = s;
                  void chrome.storage.local.set({ siteScope: s }).catch(() => undefined);
                  void refreshEvaluation();
                },
              },
            }),
          ),
        )
      : null;

  const outcome = ev ? h('span', { class: `chip ${ev.outcome}`, text: ev.outcome === 'proxy' ? 'Tunnel' : ev.outcome === 'direct' ? 'Direct' : 'Blocked' }) : null;
  const why = ev ? `${ev.reason}${ev.server && ev.outcome === 'proxy' ? ` · ${ev.server}` : ''}` : '';
  const expires = own?.expiresAt ? `Ends in ${until(own.expiresAt)}` : null;

  const seg = h('div', { class: 'segmented full', role: 'group', ariaLabel: 'Where this site goes' });
  const opts: [RuleAction | null, string][] = [
    [null, 'Auto'],
    ['proxy', 'Tunnel'],
    ['direct', 'Direct'],
    ['block', 'Block'],
  ];
  for (const [action, label] of opts) {
    seg.append(h('button', { text: label, attrs: { 'aria-pressed': String(current === action) }, on: { click: () => void setSite(action) } }));
  }

  const dur = h('select', { ariaLabel: 'For how long' });
  dur.append(h('option', { value: '0', text: 'Always' }));
  for (const m of DURATIONS) dur.append(h('option', { value: String(m), text: `For ${durationLabel(m)}` }));
  dur.value = String(siteMinutes);
  dur.onchange = () => (siteMinutes = Number(dur.value));

  const srv = h('select', { ariaLabel: 'Server for this site' });
  srv.append(h('option', { value: '', text: 'Main server' }));
  for (const s of sortedServers()) srv.append(h('option', { value: s.id, text: s.name }));
  srv.value = siteServer;
  srv.onchange = () => (siteServer = srv.value);

  return h(
    'section',
    { class: 'card stack' },
    h('div', { class: 'row' }, h('div', { class: 'label grow', text: 'This site' }), outcome),
    h('div', null, h('div', { class: 'site-host ellipsis', text: t }), h('div', { class: 'muted small ellipsis', text: expires ? `${why} · ${expires}` : why })),
    scopeRow,
    seg,
    h('div', { class: 'row' }, dur, list.servers.length > 1 ? srv : null),
  );
}

function footer(): HTMLElement {
  const item = (name: string, label: string, section: string) => h('button', { on: { click: () => openDashboard(section) } }, icon(name, 18), label);
  return h('nav', { class: 'footer' }, item('servers', 'Servers', 'servers'), item('rules', 'Rules', 'rules'), item('doctor', 'Doctor', 'doctor'), item('settings', 'Settings', 'settings'));
}

function render() {
  if (!app) return;
  const v = deriveView(app);
  $('status-dot').className = `dot ${app.blocking ? 'error' : v.tone}`;
  $('status-label').textContent = app.blocking ? 'Kill switch active' : v.label;
  const main = $('main');
  if (!ready()) {
    clear(main, runtimeCard());
    return;
  }
  clear(main, noticeBanner(), connectionCard(), siteCard(), footer());
  renderLive();
}

// ------------------------------------------------------------------ start

async function loadTab() {
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
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
  let lastRevision = -1;
  subscribe(
    (s) => {
      app = s;
      const isReady = s.runtime.kind === 'ready';
      if (isReady && !wasReady) {
        void refreshList();
        void refreshEvaluation();
        void refreshTraffic();
      }
      if (s.rules && s.rules.revision !== lastRevision) {
        lastRevision = s.rules.revision;
        void refreshEvaluation();
      }
      wasReady = isReady;
      render();
    },
    (e: NativeEvent) => {
      if (e.event === 'changed') void refreshList();
    },
  );
  trafficTimer = setInterval(() => void refreshTraffic(), 3000);
  window.addEventListener('unload', () => trafficTimer && clearInterval(trafficTimer));
}

void main();
