// MV3 service worker: owns the native messaging port and the browser proxy setting.
// Only this extension's own pages (popup, dashboard) can talk to it: there are no content
// scripts and no `externally_connectable`, and every message's sender is checked.
import type { NativeEvent, Notice, ServerList } from '../../../protocol/types';
import type { AppState, SwPush, UiMessage } from '../shared/app-state';
import { outcomeFor } from '../shared/rules-eval';
import { hostOfUrl } from '../shared/site';
import { chromeProxy, chromeWebRtc, localStore, sessionStore } from './chrome-adapters';
import { Controller } from './controller';
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

const BADGE: Record<string, { text: string; color: string; title: string }> = {
  proxy: { text: '●', color: '#8DB600', title: 'This site uses the tunnel' },
  direct: { text: '↗', color: '#6B7280', title: 'This site opens directly' },
  block: { text: '✕', color: '#D33A35', title: 'This site is blocked' },
  off: { text: '', color: '#6B7280', title: 'PerRoute: not connected' },
  hold: { text: '!', color: '#C77F00', title: 'Kill switch: tunnel sites are offline until the tunnel is back' },
};

/** Badge of one tab: where its site goes now. */
async function badgeFor(tabId: number, url: string | undefined, state: AppState) {
  let kind: keyof typeof BADGE = 'off';
  const host = hostOfUrl(url);
  if (state.blocking) kind = 'hold';
  else if (state.browserProxied && host && state.rules) {
    kind = outcomeFor(host, state.rules.rules, state.rules.mode, state.rules.localNamesDirect, Math.floor(Date.now() / 1000)).outcome;
  } else if (state.browserProxied && host) kind = 'proxy';
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
  notify: (n: Notice) => {
    if (n.kind !== 'failover' && n.kind !== 'routeDown' && n.kind !== 'ruleExpired') return;
    try {
      chrome.notifications?.create(`perroute-${Date.now()}`, {
        type: 'basic',
        iconUrl: chrome.runtime.getURL('icons/icon-128.png'),
        title: n.kind === 'failover' ? 'Switched server' : n.kind === 'routeDown' ? 'Server not responding' : 'Temporary rule ended',
        message: n.message,
        priority: n.kind === 'ruleExpired' ? 0 : 1,
      });
    } catch {
      /* notifications unavailable */
    }
  },
  extensionVersion: chrome.runtime.getManifest().version,
  setTimer: (cb, ms) => setTimeout(cb, ms),
  clearTimer: (t) => clearTimeout(t as ReturnType<typeof setTimeout>),
});

function fromOwnPage(sender: chrome.runtime.MessageSender): boolean {
  // Our own pages only (popup and dashboard). Web pages cannot message us: there is no
  // externally_connectable and no content script.
  return sender.id === chrome.runtime.id && typeof sender.url === 'string' && sender.url.startsWith(chrome.runtime.getURL(''));
}

// Registering these listeners makes Chromium start the worker at browser startup, which
// clears any stale proxy setting and (re)connects the runtime.
chrome.runtime.onStartup.addListener(() => undefined);
chrome.runtime.onInstalled.addListener(() => undefined);

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
        if (answered.size > 1000) answered.clear();
        response = { authCredentials: creds };
      }
    }
    callback?.(response);
    return undefined;
  },
  { urls: ['<all_urls>'] },
  ['asyncBlocking'],
);

// Request inspector (memory only).
const hostOf = (url: string) => hostOfUrl(url) ?? '';
chrome.webRequest.onBeforeRequest.addListener(
  (d) => {
    if (d.type === 'main_frame' && d.tabId >= 0) inspector.navigated(d.tabId);
    return undefined;
  },
  { urls: ['<all_urls>'], types: ['main_frame'] },
);
chrome.webRequest.onCompleted.addListener((d) => inspector.completed(d.tabId, hostOf(d.url), d.type, d.statusCode, Date.now()), { urls: ['<all_urls>'] });
chrome.webRequest.onErrorOccurred.addListener((d) => inspector.failed(d.tabId, hostOf(d.url), d.type, d.error, Date.now()), { urls: ['<all_urls>'] });
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

// Subscriptions refresh by themselves: hourly check against the interval in Settings (or the one
// the provider asks for, if shorter).
chrome.alarms?.create('subscriptions', { periodInMinutes: 60, delayInMinutes: 2 });
chrome.alarms?.onAlarm.addListener(async (a) => {
  if (a.name !== 'subscriptions' || controller.state.runtime.kind !== 'ready') return;
  const hours = controller.state.settings?.subscriptionUpdateHours ?? 24;
  if (hours <= 0) return;
  const r = await controller.request('listServers', {});
  if (!r.ok) return;
  const now = Date.now() / 1000;
  for (const s of (r.result as ServerList).subscriptions) {
    const every = Math.min(hours, s.updateIntervalHours ?? hours) * 3600;
    if (!s.lastUpdated || now - s.lastUpdated >= every) await controller.request('updateSubscription', { id: s.id });
  }
});

void controller.start();
