// Real implementations of the Controller dependencies on top of chrome.* APIs.
import { CLOSED_PORT, type BlockPlan, type KeyValue, type ProxyControl, type WebRtcControl } from './controller';

/** Destinations that never go through the tunnel (mirrors helper/src/core/xray_config.rs PRIVATE_CIDRS). */
export const BYPASS_LIST = [
  '<local>',
  'localhost',
  '127.0.0.0/8',
  '[::1]',
  '10.0.0.0/8',
  '172.16.0.0/12',
  '192.168.0.0/16',
  '169.254.0.0/16',
  '100.64.0.0/10',
  'fc00::/7',
  'fe80::/10',
];

export function proxyConfig(port: number): chrome.proxy.ProxyConfig {
  return {
    mode: 'fixed_servers',
    rules: {
      // HTTP proxy with per-connection credentials (answered in onAuthRequired). Hostnames are sent
      // to the proxy unresolved (CONNECT host:port / absolute URI), so DNS stays on the server.
      singleProxy: { scheme: 'http', host: '127.0.0.1', port },
      bypassList: BYPASS_LIST,
    },
  };
}

/** Kill switch: tunnel traffic goes to a closed loopback port, so it fails instead of going direct. */
export function blockConfig(plan: BlockPlan): chrome.proxy.ProxyConfig {
  if (plan.mode === 'pac') return { mode: 'pac_script', pacScript: { data: plan.script, mandatory: true } };
  return { mode: 'fixed_servers', rules: { singleProxy: { scheme: 'http', host: '127.0.0.1', port: CLOSED_PORT }, bypassList: BYPASS_LIST } };
}

type LevelOfControl ='not_controllable' | 'controlled_by_other_extensions' | 'controllable_by_this_extension' | 'controlled_by_this_extension';

export function controlProblem(level: LevelOfControl | string): string | null {
  switch (level) {
    case 'not_controllable':
      return "The browser's proxy settings are managed by policy and cannot be changed by the extension.";
    case 'controlled_by_other_extensions':
      return 'Another extension controls the browser proxy. Disable it, then connect again.';
    default:
      return null;
  }
}

export const chromeProxy: ProxyControl = {
  async set(port) {
    const before = await chrome.proxy.settings.get({ incognito: false });
    const problem = controlProblem(before.levelOfControl);
    if (problem) return problem;
    await chrome.proxy.settings.set({ value: proxyConfig(port), scope: 'regular' });
    const after = await chrome.proxy.settings.get({ incognito: false });
    if (after.levelOfControl !== 'controlled_by_this_extension') {
      return controlProblem(after.levelOfControl) ?? 'Could not apply the browser proxy settings.';
    }
    return null;
  },
  async block(plan) {
    const before = await chrome.proxy.settings.get({ incognito: false });
    const problem = controlProblem(before.levelOfControl);
    if (problem) return problem;
    await chrome.proxy.settings.set({ value: blockConfig(plan), scope: 'regular' });
    return null;
  },
  async clear() {
    await chrome.proxy.settings.clear({ scope: 'regular' });
  },
  async controlProblem() {
    const now = await chrome.proxy.settings.get({ incognito: false });
    if (now.levelOfControl === 'controlled_by_this_extension') return null;
    return controlProblem(now.levelOfControl) ?? 'The browser proxy setting is no longer controlled by PerRoute.';
  },
};

export const chromeWebRtc: WebRtcControl = {
  async apply(protect) {
    // "privacy" is a required permission; guard anyway in case a policy removes the API.
    const net = (chrome as unknown as { privacy?: typeof chrome.privacy }).privacy?.network;
    if (!net) return;
    try {
      if (protect) {
        await net.webRTCIPHandlingPolicy.set({ value: 'disable_non_proxied_udp' });
      } else {
        await net.webRTCIPHandlingPolicy.clear({});
      }
    } catch {
      /* not controllable (policy) – problem() reports it */
    }
  },
  async problem() {
    const net = (chrome as unknown as { privacy?: typeof chrome.privacy }).privacy?.network;
    if (!net) return 'WebRTC leak protection is unavailable in this browser.';
    const now = await net.webRTCIPHandlingPolicy.get({});
    // A policy may enforce the same (or the protection may simply be ours): both are fine.
    if (now.value === 'disable_non_proxied_udp') return null;
    if (now.levelOfControl === 'controlled_by_other_extensions') {
      return 'Another extension changed the WebRTC setting, so your real IP address could leak.';
    }
    if (now.levelOfControl === 'not_controllable') {
      return 'A browser policy controls the WebRTC setting, so your real IP address could leak. Turn off WebRTC protection in Settings to connect anyway.';
    }
    return 'WebRTC leak protection is not in effect.';
  },
};

function store(areaOf: () => chrome.storage.StorageArea | undefined): KeyValue {
  return {
    async get(key) {
      const area = areaOf();
      if (!area) return undefined;
      return (await area.get(key))[key];
    },
    async set(key, value) {
      await areaOf()?.set({ [key]: value });
    },
  };
}

export const sessionStore = store(() => chrome.storage.session);
export const localStore = store(() => chrome.storage.local);
