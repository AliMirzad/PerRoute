// Shared state of the dashboard page: the service worker's state, the server list and the rules,
// refreshed on helper events. Sections render from here.
import type { NativeEvent, RulesState, ServerList, ServerSummary, TestResult } from '../../../protocol/types';
import type { AppState } from '../shared/app-state';
import { request } from '../shared/client';

export interface Ctx {
  app: AppState | null;
  list: ServerList;
  rules: RulesState | null;
  /** Latest test result per server (from progress events). */
  tests: Map<string, TestResult & { kind: 'tcp' | 'real' | 'speed'; at: number; bps?: number }>;
  rerender(): void;
}

export const ctx: Ctx = {
  app: null,
  list: { servers: [], subscriptions: [], selectedServerId: null },
  rules: null,
  tests: new Map(),
  rerender: () => undefined,
};

export const isReady = () => ctx.app?.runtime.kind === 'ready';
export const isConnected = () => ctx.app?.status?.state === 'connected';
export const activeServerId = () => {
  const st = ctx.app?.status;
  return st && (st.state === 'connected' || st.state === 'connecting') ? (st.serverId ?? null) : null;
};

export function serverName(id: string | null | undefined): string {
  if (!id) return '';
  return ctx.list.servers.find((s) => s.id === id)?.name ?? 'Removed server';
}

export function sortedServers(): ServerSummary[] {
  const fav = new Set(ctx.list.favorites ?? []);
  return [...ctx.list.servers].sort((a, b) => Number(fav.has(b.id)) - Number(fav.has(a.id)));
}

export async function refreshList() {
  if (!isReady()) return;
  const r = await request('listServers', {});
  if (r.ok) ctx.list = r.result as ServerList;
  ctx.rerender();
}

export async function refreshRules() {
  if (!isReady()) return;
  const r = await request('getRules', {});
  if (r.ok) ctx.rules = r.result as RulesState;
  ctx.rerender();
}

/** Sections that keep their own data subscribe here for helper events. */
const eventListeners = new Set<(e: NativeEvent) => void>();
export function onEvent(fn: (e: NativeEvent) => void): () => void {
  eventListeners.add(fn);
  return () => eventListeners.delete(fn);
}
export function dispatchEvent(e: NativeEvent) {
  if (e.event === 'progress') {
    const v = e.progress.value;
    const prev = ctx.tests.get(v.serverId);
    ctx.tests.set(v.serverId, { ...v, kind: prev?.kind === 'tcp' || prev?.kind === 'real' ? prev.kind : 'real', at: Date.now() });
  }
  for (const fn of eventListeners) fn(e);
}
