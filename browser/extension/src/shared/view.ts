// Pure mapping from service-worker state to what the popup shows. Unit-tested.
import type { ApiError, ServerList, ServerSummary, SubscriptionSummary } from '../../../protocol/types';
import type { AppState } from './app-state';

export type Tone = 'ok' | 'busy' | 'idle' | 'error';
export type PrimaryAction = 'connect' | 'disconnect' | 'cancel' | 'retry' | 'none';

export interface View {
  label: string;
  tone: Tone;
  detail: string | null;
  action: PrimaryAction;
  /** Short hint shown under an error. */
  hint: string | null;
  /** Server list, import and settings are usable. */
  interactive: boolean;
}

const ERROR_TITLES: Partial<Record<ApiError['code'], string>> = {
  XRAY_FAILED: 'Xray failed',
  XRAY_MISSING: 'Xray is missing',
  XRAY_CONFIG_REJECTED: 'Invalid configuration',
  INVALID_CONFIG: 'Invalid configuration',
  SERVER_UNREACHABLE: 'Server unreachable',
  PORT_UNAVAILABLE: 'Local port unavailable',
  SECURE_STORAGE_UNAVAILABLE: 'Secure storage unavailable',
  SUBSCRIPTION_FAILED: 'Subscription update failed',
};

const ERROR_HINTS: Partial<Record<ApiError['code'], string>> = {
  SERVER_UNREACHABLE: 'Check that the server is online and the configuration is current.',
  XRAY_FAILED: 'Try connecting again. If it keeps failing, run the PerRoute installer again.',
  XRAY_MISSING: 'Run the PerRoute installer again.',
  XRAY_CONFIG_REJECTED: 'Ask the server operator for an updated link.',
};

export function errorTitle(e: ApiError): string {
  return ERROR_TITLES[e.code] ?? 'Error';
}

export function deriveView(s: AppState): View {
  const r = s.runtime;
  switch (r.kind) {
    case 'starting':
      return { label: 'Starting…', tone: 'busy', detail: null, action: 'none', hint: null, interactive: false };
    case 'missing':
      return {
        label: 'Runtime not installed',
        tone: 'error',
        detail: r.message,
        action: 'retry',
        hint: 'Run the PerRoute installer and choose "Browser extension", then click Retry.',
        interactive: false,
      };
    case 'forbidden':
      return { label: 'Runtime not authorized', tone: 'error', detail: r.message, action: 'retry', hint: null, interactive: false };
    case 'incompatible':
      return { label: 'Update required', tone: 'error', detail: r.message, action: 'none', hint: null, interactive: false };
    case 'crashed':
      return {
        label: 'Runtime stopped',
        tone: 'error',
        detail: r.message,
        action: 'retry',
        hint: r.retryInMs ? `Retrying in ${Math.round(r.retryInMs / 1000)} s…` : null,
        interactive: false,
      };
    case 'ready':
      break;
  }
  const st = s.status;
  if (!st) return { label: 'Starting…', tone: 'busy', detail: null, action: 'none', hint: null, interactive: true };
  if (s.blocking) {
    return {
      label: 'Tunnel down',
      tone: 'error',
      detail: 'Kill switch: sites that use the tunnel stay offline until it is back. Disconnect to browse without it.',
      action: 'disconnect',
      hint: null,
      interactive: true,
    };
  }
  if (s.proxyError) {
    return { label: 'Browser proxy blocked', tone: 'error', detail: s.proxyError, action: 'connect', hint: null, interactive: true };
  }
  switch (st.state) {
    case 'connected':
      return { label: 'Connected', tone: 'ok', detail: null, action: 'disconnect', hint: null, interactive: true };
    case 'connecting': {
      const detail = st.phase === 'verifying' ? 'Verifying connection…' : st.phase === 'restarting' ? 'Restarting Xray…' : 'Starting Xray…';
      return { label: 'Connecting', tone: 'busy', detail, action: 'cancel', hint: null, interactive: true };
    }
    case 'disconnecting':
      return { label: 'Disconnecting', tone: 'busy', detail: null, action: 'none', hint: null, interactive: true };
    case 'error': {
      const e = st.error ?? { code: 'INTERNAL', message: 'Unknown error' };
      return { label: errorTitle(e), tone: 'error', detail: e.message, action: 'connect', hint: ERROR_HINTS[e.code] ?? null, interactive: true };
    }
    default:
      return { label: 'Disconnected', tone: 'idle', detail: null, action: 'connect', hint: null, interactive: true };
  }
}

/** Text of the main connect button. */
export function primaryLabel(a: PrimaryAction): string {
  return a === 'connect' ? 'Connect' : a === 'disconnect' ? 'Disconnect' : a === 'cancel' ? 'Cancel' : a === 'retry' ? 'Retry' : 'Please wait…';
}

/** "12 min", "3 h 05 min" since a Unix time in seconds. */
export function connectedFor(sinceSec: number | undefined, nowMs = Date.now()): string | null {
  if (!sinceSec) return null;
  const d = Math.max(0, Math.floor(nowMs / 1000 - sinceSec));
  if (d < 60) return 'under a minute';
  const m = Math.floor(d / 60);
  if (m < 60) return `${m} min`;
  const hours = Math.floor(m / 60);
  if (hours < 48) return `${hours} h ${String(m % 60).padStart(2, '0')} min`;
  return `${Math.floor(hours / 24)} days`;
}

export interface SubscriptionHealth {
  tone: 'ok' | 'warn' | 'fail';
  label: string;
  detail: string;
}

/**
 * Subscription intelligence: expired, used up, failing, about to run out or suddenly changed.
 * Mirrors the desktop app's SubscriptionHealth.
 */
export function subscriptionHealth(s: SubscriptionSummary, nowSec = Date.now() / 1000): SubscriptionHealth {
  const u = s.usage;
  const used = (u?.upload ?? 0) + (u?.download ?? 0);
  const share = u?.total ? used / u.total : null;
  const expire = u?.expire ?? null;
  if (expire && expire <= nowSec) return { tone: 'fail', label: 'Expired', detail: 'The subscription has expired. Renew it with your provider.' };
  if (share !== null && share >= 1) return { tone: 'fail', label: 'Used up', detail: 'All data of this subscription is used up.' };
  if (s.lastError) return { tone: 'fail', label: 'Failing', detail: `The last update failed: ${s.lastError}` };
  if (expire && expire - nowSec < 3 * 86400) {
    const days = Math.max(1, Math.ceil((expire - nowSec) / 86400));
    return { tone: 'warn', label: 'Expires soon', detail: `The subscription expires in ${days === 1 ? 'a day' : `${days} days`}.` };
  }
  if (share !== null && share >= 0.9) return { tone: 'warn', label: 'Almost used up', detail: `${Math.round(share * 100)}% of the data is used.` };
  const last = s.history?.[s.history.length - 1];
  if (last && !last.error) {
    const before = s.serverCount - last.added + last.removed;
    if (before >= 4 && last.removed >= Math.max(3, before * 0.5)) {
      return { tone: 'warn', label: 'Changed a lot', detail: `The last update removed ${last.removed} of ${before} servers.` };
    }
  }
  if (s.lastUpdated && nowSec - s.lastUpdated > 7 * 86400) return { tone: 'warn', label: 'Not updated', detail: 'Not updated for more than a week.' };
  if (s.serverCount === 0) return { tone: 'warn', label: 'Empty', detail: 'The subscription has no usable servers.' };
  return { tone: 'ok', label: 'OK', detail: 'Working.' };
}

/** All tags in use, sorted. */
export function tagsInUse(list: ServerList): string[] {
  return [...new Set(Object.values(list.tags ?? {}).filter(Boolean))].sort((a, b) => a.localeCompare(b));
}

/** Favorites first, then the original order. */
export function sortedServers(list: ServerList): ServerSummary[] {
  const fav = new Set(list.favorites ?? []);
  return [...list.servers].sort((a, b) => Number(fav.has(b.id)) - Number(fav.has(a.id)));
}

export const PROTOCOL_NAMES: Record<ServerSummary['protocol'], string> = { vless: 'VLESS', vmess: 'VMess', trojan: 'Trojan', shadowsocks: 'Shadowsocks' };

export function protocolLine(s: ServerSummary): string {
  const proto = PROTOCOL_NAMES[s.protocol] ?? s.protocol;
  if (s.protocol === 'shadowsocks') return proto;
  const parts = [proto];
  if (s.security !== 'none') parts.push(s.securityLabel);
  parts.push(s.transportLabel);
  if (s.flow) parts.push('Vision');
  return parts.join(' · ');
}

/** Which server the main selector shows: the active one while connected/connecting, else the selection. */
export function currentServerId(s: AppState, selected: string | null): string | null {
  const st = s.status;
  if (st && (st.state === 'connected' || st.state === 'connecting') && st.serverId) return st.serverId;
  return selected;
}

// ------------------------------------------------------------------ server list filter

/** "all", "manual" (imported by hand: links, JSON, QR) or "sub:<subscription id>". */
export type ServerFilter = string;

export interface FilterOption {
  value: ServerFilter;
  label: string;
}

export function filterOptions(list: ServerList): FilterOption[] {
  const manual = list.servers.filter((s) => !s.subscriptionId).length;
  const opts: FilterOption[] = [{ value: 'all', label: `All servers (${list.servers.length})` }];
  // Offered only when there are hand-added servers; subscriptions are always listed (even with
  // no servers) so they can be chosen and updated.
  if (manual > 0) opts.push({ value: 'manual', label: `Manually added (${manual})` });
  for (const sub of list.subscriptions) {
    const n = list.servers.filter((s) => s.subscriptionId === sub.id).length;
    opts.push({ value: `sub:${sub.id}`, label: `Subscription: ${sub.name} (${n})` });
  }
  return opts;
}

/** Unknown filters (e.g. a deleted subscription) fall back to "all". */
export function normalizeFilter(list: ServerList, f: ServerFilter | null | undefined): ServerFilter {
  if (f === 'manual') return list.servers.some((s) => !s.subscriptionId) ? f : 'all';
  if (f && f.startsWith('sub:') && list.subscriptions.some((s) => `sub:${s.id}` === f)) return f;
  return 'all';
}

export function filterServers(list: ServerList, f: ServerFilter): ServerSummary[] {
  if (f === 'manual') return list.servers.filter((s) => !s.subscriptionId);
  if (f.startsWith('sub:')) {
    const id = f.slice(4);
    return list.servers.filter((s) => s.subscriptionId === id);
  }
  return list.servers;
}
