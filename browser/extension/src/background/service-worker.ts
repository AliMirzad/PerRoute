// MV3 service worker: owns the native messaging port and the browser proxy setting.
// Only this extension's own pages (popup, dashboard) can talk to it: there are no content
// scripts and no `externally_connectable`, and every message's sender is checked.
import type { NativeEvent, Notice, RuleAction, ServerList, SubscriptionSummary } from '../../../protocol/types';
import type { AppState, SwPush, UiMessage } from '../shared/app-state';
import { outcomeFor, portOfUrl, siteRuleFor } from '../shared/rules-eval';
import { hostOfUrl, siteOf } from '../shared/site';
import { subscriptionHealth } from '../shared/view';
import { chromeProxy, chromeWebRtc, localStore, sessionStore } from './chrome-adapters';
import { Controller, TRIAL_ALARM } from './controller';
import { Inspector } from './inspector';

const uiPorts = new Set<chrome.runtime.Port>();
const inspector = new Inspector();

function push(msg: SwPush) {
  for (const p of uiPorts) {
    try {
      p.postMessage(msg);
    } catch {
      uiPorts.delete(p);
    }
  }
}

// ------------------------------------------------------------------ toolbar badge

const BADGE: Record<string, { text: string; color: string; title: string }> = {
  proxy: { text: '●', color: '#5E8C00', title: 'PerRoute: this site uses the tunnel' },
  direct: { text: '↗', color: '#6B7280', title: 'PerRoute: this site opens directly' },
  block: { text: '✕', color: '#D33A35', title: 'PerRoute: this site is blocked' },
  off: { text: '', color: '#6B7280', title: 'PerRoute: not connected' },
  hold: { text: '!', color: '#C77F00', title: 'PerRoute kill switch: this site stays offline until the tunnel is back' },
};

/** Badge of one tab: where its site goes now. */
async function badgeFor(tabId: number, url: string | undefined, state: AppState) {
  let kind: keyof typeof BADGE = 'off';
  const host = hostOfUrl(url);
  if (host && (state.browserProxied || state.blocking)) {
    const r = state.rules;
    const outcome = r ? outcomeFor(host, r.rules, r.mode, r.localNamesDirect, Math.floor(Date.now() / 1000), portOfUrl(url)).outcome : 'proxy';
    // While the kill switch holds, everything that would use the tunnel is offline.
    kind = state.blocking && outcome !== 'direct' ? 'hold' : outcome;
  }
  const b = BADGE[kind];
  try {
    await chrome.action.setBadgeText({ tabId, text: b.text });
    await chrome.action.setBadgeBackgroundColor({ tabId, color: b.color });
    await chrome.action.setTitle({ tabId, title: b.title });
  } catch {
    /* the tab closed */
  }
}

async function refreshBadges(state: AppState) {
  try {
    const tabs = await chrome.tabs.query({ active: true });
    await Promise.all(tabs.map((t) => (t.id === undefined ? undefined : badgeFor(t.id, t.url, state))));
  } catch {
    /* no tabs API in this context */
  }
}

// ------------------------------------------------------------------ notifications

const NOTICE_TITLES: Record<string, string> = {
  failover: 'Switched server',
  routeDown: 'Server not responding',
  ruleExpired: 'Temporary rule ended',
  trialEnded: 'Trial ended',
  subscription: 'Subscription',
  shortcut: 'PerRoute',
};

function notify(n: Notice) {
  if (!(n.kind in NOTICE_TITLES)) return;
  try {
    chrome.notifications?.create(`perroute-${n.kind}-${Date.now()}`, {
      type: 'basic',
      iconUrl: chrome.runtime.getURL('icons/icon-128.png'),
      title: NOTICE_TITLES[n.kind],
      message: n.message,
      priority: n.kind === 'failover' || n.kind === 'routeDown' ? 1 : 0,
    });
  } catch {
    /* notifications unavailable */
  }
}

// ------------------------------------------------------------------ controller

const controller = new Controller({
  connectNative: (host) => chrome.runtime.connectNative(host),
  lastError: () => chrome.runtime.lastError?.message,
  proxy: chromeProxy,
  webrtc: chromeWebRtc,
  session: sessionStore,
  local: localStore,
  broadcast: (state: AppState) => {
    push({ type: 'state', state });
    void refreshBadges(state);
  },
  broadcastEvent: (event: NativeEvent) => push({ type: 'event', event }),
  notify,
  extensionVersion: chrome.runtime.getManifest().version,
  setTimer: (cb, ms) => setTimeout(cb, ms),
  clearTimer: (t) => clearTimeout(t as ReturnType<typeof setTimeout>),
  schedule: (name, at) => void chrome.alarms?.create(name, { when: Math.max(at, Date.now() + 1000) }),
  unschedule: (name) => void chrome.alarms?.clear(name),
});

function fromOwnPage(sender: chrome.runtime.MessageSender): boolean {
  // Our own pages only (popup and dashboard). Web pages cannot message us: there is no
  // externally_connectable and no content script.
  return sender.id === chrome.runtime.id && typeof sender.url === 'string' && sender.url.startsWith(chrome.runtime.getURL(''));
}

// Registering these listeners makes Chromium start the worker at browser startup, which
// clears any stale proxy setting and (re)connects the runtime.
chrome.runtime.onStartup.addListener(() => undefined);
chrome.runtime.onInstalled.addListener(() => setupMenus());

chrome.runtime.onConnect.addListener((port) => {
  if (port.name !== 'ui' || !port.sender || !fromOwnPage(port.sender)) {
    port.disconnect();
    return;
  }
  uiPorts.add(port);
  port.onDisconnect.addListener(() => uiPorts.delete(port));
  const msg: SwPush = { type: 'state', state: controller.state };
  port.postMessage(msg);
  const k = controller.state.runtime.kind;
  if (k === 'missing' || k === 'forbidden' || k === 'crashed') controller.retry();
});

chrome.runtime.onMessage.addListener((raw: unknown, sender, sendResponse) => {
  if (!fromOwnPage(sender)) return false;
  const msg = raw as UiMessage;
  switch (msg?.type) {
    case 'request':
      controller.request(String(msg.cmd), msg.args ?? {}).then(sendResponse);
      return true; // async response
    case 'tryServer':
      controller.tryServer(msg.serverId, Number(msg.minutes)).then(sendResponse);
      return true;
    case 'endTrial':
      controller.endTrial().then(() => sendResponse({ ok: true }));
      return true;
    case 'retryNative':
      controller.retry();
      sendResponse({ ok: true });
      return false;
    case 'getState':
      sendResponse(controller.state);
      return false;
    case 'inspector':
      sendResponse(typeof msg.tabId === 'number' ? inspector.list(msg.tabId) : []);
      return false;
    case 'clearInspector':
      if (typeof msg.tabId === 'number') inspector.navigated(msg.tabId);
      sendResponse({ ok: true });
      return false;
    case 'dismissNotices':
      controller.dismissNotices();
      sendResponse({ ok: true });
      return false;
    default:
      return false;
  }
});

// ------------------------------------------------------------------ proxy authentication

// Answer the tunnel inbound's 407 challenge with the per-connection credentials. Nothing else is
// answered (other proxies, and every website's own authentication, get the browser default), and a
// request challenged twice is cancelled so wrong credentials can never loop.
const answered = new Set<string>();
chrome.webRequest.onAuthRequired.addListener(
  (details, callback) => {
    let response: chrome.webRequest.BlockingResponse = {};
    const creds = details.isProxy && details.challenger ? controller.proxyCredentials(details.challenger) : null;
    if (creds) {
      if (answered.has(details.requestId)) {
        answered.delete(details.requestId);
        response = { cancel: true };
      } else {
        answered.add(details.requestId);
        if (answered.size > 5000) answered.clear();
        response = { authCredentials: creds };
      }
    }
    callback?.(response);
    return undefined;
  },
  { urls: ['<all_urls>'] },
  ['asyncBlocking'],
);

// ------------------------------------------------------------------ request inspector (memory only)

const hostOf = (url: string) => hostOfUrl(url) ?? '';
chrome.webRequest.onBeforeRequest.addListener(
  (d) => {
    if (d.type === 'main_frame' && d.tabId >= 0) inspector.navigated(d.tabId);
    return undefined;
  },
  { urls: ['<all_urls>'], types: ['main_frame'] },
);
chrome.webRequest.onCompleted.addListener(
  (d) => {
    answered.delete(d.requestId);
    inspector.completed(d.tabId, hostOf(d.url), d.type, d.statusCode, Date.now());
  },
  { urls: ['<all_urls>'] },
);
chrome.webRequest.onErrorOccurred.addListener(
  (d) => {
    answered.delete(d.requestId);
    inspector.failed(d.tabId, hostOf(d.url), d.type, d.error, Date.now());
  },
  { urls: ['<all_urls>'] },
);
chrome.tabs.onRemoved.addListener((tabId) => inspector.closed(tabId));

// Toolbar badge per tab: where the current site goes.
chrome.tabs.onActivated.addListener(({ tabId }) => {
  chrome.tabs.get(tabId).then((t) => badgeFor(tabId, t.url, controller.state), () => undefined);
});
chrome.tabs.onUpdated.addListener((tabId, info, tab) => {
  if (info.url || info.status === 'complete') void badgeFor(tabId, tab.url, controller.state);
});

// Another extension or a policy can take over the browser proxy at any time; never keep showing
// "connected" when our setting is no longer in effect.
chrome.proxy.settings.onChange.addListener(() => controller.proxyControlChanged());
// Same for the WebRTC leak protection (another extension can override it silently).
chrome.privacy?.network?.webRTCIPHandlingPolicy?.onChange.addListener(() => controller.proxyControlChanged());

// ------------------------------------------------------------------ one-click site routing

async function siteScope(): Promise<'site' | 'host'> {
  try {
    const { siteScope: s } = await chrome.storage.local.get('siteScope');
    return s === 'host' ? 'host' : 'site';
  } catch {
    return 'site';
  }
}

/** Sets where a site goes, from the context menu or a keyboard shortcut. */
async function routeSite(url: string | undefined, action: RuleAction | null, minutes: number | null = null) {
  const host = hostOfUrl(url);
  if (!host) return notify({ kind: 'shortcut', message: 'This page is not a website.' });
  const target = (await siteScope()) === 'site' ? siteOf(host) : host;
  const r = await controller.request('setSite', { host: target, action, serverId: null, minutes });
  if (!r.ok) return notify({ kind: 'shortcut', message: r.error.message });
  const what = action === null ? 'follows your rules again' : action === 'proxy' ? 'uses the tunnel' : action === 'direct' ? 'opens directly' : 'is blocked';
  notify({ kind: 'shortcut', message: `${target} ${what}${minutes ? ` for ${minutes / 60} h` : ''}.` });
}

const MENU: { id: string; title: string; action?: RuleAction | null; minutes?: number; separator?: boolean }[] = [
  { id: 'site-proxy', title: 'Use the tunnel for this site', action: 'proxy' },
  { id: 'site-proxy-1h', title: 'Use the tunnel for this site for 1 hour', action: 'proxy', minutes: 60 },
  { id: 'site-direct', title: 'Open this site directly', action: 'direct' },
  { id: 'site-block', title: 'Block this site', action: 'block' },
  { id: 'site-auto', title: 'Let my rules decide', action: null },
  { id: 'sep-1', title: '', separator: true },
  { id: 'open', title: 'Open PerRoute' },
];

function setupMenus() {
  if (!chrome.contextMenus) return;
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({ id: 'perroute', title: 'PerRoute', contexts: ['page', 'link', 'frame'], documentUrlPatterns: ['http://*/*', 'https://*/*'] });
    for (const m of MENU) {
      chrome.contextMenus.create(m.separator ? { id: m.id, type: 'separator', parentId: 'perroute', contexts: ['page', 'link', 'frame'] } : { id: m.id, parentId: 'perroute', title: m.title, contexts: ['page', 'link', 'frame'] });
    }
  });
}

chrome.contextMenus?.onClicked.addListener((info, tab) => {
  const m = MENU.find((x) => x.id === info.menuItemId);
  if (!m) return;
  if (m.id === 'open') {
    void chrome.runtime.openOptionsPage();
    return;
  }
  // On a link, the link's site; otherwise the page's.
  void routeSite(info.linkUrl ?? info.frameUrl ?? info.pageUrl ?? tab?.url, m.action ?? null, m.minutes ?? null);
});

// Keyboard shortcuts (chrome://extensions/shortcuts).
chrome.commands?.onCommand.addListener(async (command, tab) => {
  const st = controller.state;
  if (st.runtime.kind !== 'ready') return notify({ kind: 'shortcut', message: 'The PerRoute runtime is not available.' });
  if (command === 'toggle-connection') {
    const s = st.status?.state;
    if (s === 'connected' || s === 'connecting' || st.blocking) {
      await controller.request('disconnect', {});
      return;
    }
    const list = await controller.request('listServers', {});
    const servers = list.ok ? (list.result as ServerList) : null;
    const id = st.status?.serverId ?? servers?.selectedServerId ?? servers?.servers[0]?.id;
    if (st.settings?.fastestOnConnect && (servers?.servers.length ?? 0) > 1) await controller.request('connectFastest', {});
    else if (id) {
      const r = await controller.request('connect', { serverId: id });
      if (!r.ok) notify({ kind: 'shortcut', message: r.error.message });
    } else notify({ kind: 'shortcut', message: 'Add a server first.' });
  } else if (command === 'connect-fastest') {
    const r = await controller.request('connectFastest', {});
    if (!r.ok) notify({ kind: 'shortcut', message: r.error.message });
  } else if (command === 'toggle-site') {
    // Tunnel <-> direct for the current site.
    const host = hostOfUrl(tab?.url);
    if (!host || !st.rules) return routeSite(tab?.url, null);
    const target = (await siteScope()) === 'site' ? siteOf(host) : host;
    const own = siteRuleFor(st.rules.rules, target);
    const now = own?.action ?? outcomeFor(host, st.rules.rules, st.rules.mode, st.rules.localNamesDirect, Math.floor(Date.now() / 1000), portOfUrl(tab?.url)).outcome;
    await routeSite(tab?.url, now === 'proxy' ? 'direct' : 'proxy');
  }
});

// ------------------------------------------------------------------ alarms

// Subscriptions refresh by themselves: an hourly check against the interval in Settings (or the
// one the provider asks for, if shorter). The alarm is created once; recreating it on every
// service-worker start would keep pushing it back.
chrome.alarms?.get('subscriptions').then((a) => {
  if (!a) void chrome.alarms.create('subscriptions', { periodInMinutes: 60, delayInMinutes: 2 });
});

function saveData(): boolean {
  return (navigator as Navigator & { connection?: { saveData?: boolean } }).connection?.saveData === true;
}

async function warnSubscriptions(subs: SubscriptionSummary[]) {
  const day = new Date().toISOString().slice(0, 10);
  let warned: Record<string, string> = {};
  try {
    warned = ((await chrome.storage.local.get('subWarned')).subWarned as Record<string, string>) ?? {};
  } catch {
    /* preference only */
  }
  const next: Record<string, string> = {};
  for (const s of subs) {
    const h = subscriptionHealth(s);
    if (h.tone === 'ok') continue;
    const key = `${s.id}:${h.label}`;
    const last = warned[key];
    // Once per problem; a serious one (expired, used up, failing) again each day it lasts.
    const due = !last || (h.tone === 'fail' && last !== day);
    next[key] = due ? day : last;
    if (due) notify({ kind: 'subscription', message: `${s.name}: ${h.detail}` });
  }
  try {
    await chrome.storage.local.set({ subWarned: next });
  } catch {
    /* preference only */
  }
}

chrome.alarms?.onAlarm.addListener(async (a) => {
  if (a.name === TRIAL_ALARM) {
    await controller.endTrial();
    return;
  }
  if (a.name !== 'subscriptions' || controller.state.runtime.kind !== 'ready') return;
  const hours = controller.state.settings?.subscriptionUpdateHours ?? 24;
  const r = await controller.request('listServers', {});
  if (!r.ok) return;
  let subs = (r.result as ServerList).subscriptions;
  // Resource saving: with Data Saver on, background updates wait.
  if (hours > 0 && !saveData()) {
    const now = Date.now() / 1000;
    let updated = false;
    for (const s of subs) {
      const every = Math.min(hours, s.updateIntervalHours ?? hours) * 3600;
      if (!s.lastUpdated || now - s.lastUpdated >= every) {
        await controller.request('updateSubscription', { id: s.id });
        updated = true;
      }
    }
    if (updated) {
      const again = await controller.request('listServers', {});
      if (again.ok) subs = (again.result as ServerList).subscriptions;
    }
  }
  await warnSubscriptions(subs);
});

setupMenus();
void controller.start();
