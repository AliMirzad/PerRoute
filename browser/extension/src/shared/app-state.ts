import type { HelloResult, NativeEvent, NativeStatus, Notice, RulesState, Settings } from '../../../protocol/types';

/** Availability of the native runtime, as seen by the service worker. */
export type RuntimeState =
  | { kind: 'starting' }
  | { kind: 'ready'; hello: HelloResult }
  | { kind: 'missing'; message: string }
  | { kind: 'forbidden'; message: string }
  | { kind: 'incompatible'; message: string }
  | { kind: 'crashed'; message: string; retryInMs: number | null };

export interface AppState {
  runtime: RuntimeState;
  /** Latest status from the helper (null until the first status arrives). */
  status: NativeStatus | null;
  /** Problem applying the browser proxy (e.g. another extension controls it). */
  proxyError: string | null;
  /** True while the browser is actually routed through the local proxy. */
  browserProxied: boolean;
  /** True while the kill switch keeps tunnel sites offline (the tunnel is down but wanted). */
  blocking: boolean;
  /** The helper's settings (null until loaded). */
  settings: Settings | null;
  /** Site rules, for the toolbar badge and the request inspector. */
  rules: RulesState | null;
  /** Recent notices (failover, expired rules), newest first. */
  notices: (Notice & { at: number })[];
  extensionVersion: string;
}

export const EXTENSION_PROTOCOL_VERSION = 4;

/** One host seen in a tab, for the request inspector. */
export interface InspectorEntry {
  host: string;
  requests: number;
  failed: number;
  blocked: number;
  lastStatus: number | null;
  lastError: string | null;
  types: string[];
  lastAt: number;
}

/** Messages from extension pages to the service worker. */
export type UiMessage =
  | { type: 'request'; cmd: string; args: Record<string, unknown> }
  | { type: 'retryNative' }
  | { type: 'getState' }
  | { type: 'inspector'; tabId: number }
  | { type: 'clearInspector'; tabId: number }
  | { type: 'dismissNotices' };

/** Messages pushed from the service worker to open extension pages. */
export type SwPush = { type: 'state'; state: AppState } | { type: 'event'; event: NativeEvent };
