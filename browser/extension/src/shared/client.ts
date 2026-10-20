// How extension pages (popup, dashboard) talk to the service worker.
import type { CommandName, Commands, NativeEvent, NativeResponse } from '../../../protocol/types';
import type { AppState, InspectorEntry, SwPush } from './app-state';

export function request<K extends CommandName>(cmd: K, args: Commands[K]): Promise<NativeResponse> {
  return chrome.runtime.sendMessage({ type: 'request', cmd, args }) as Promise<NativeResponse>;
}

/** Like `request`, but resolves to the result and throws the helper's message on failure. */
export async function call<T = unknown, K extends CommandName = CommandName>(cmd: K, args: Commands[K]): Promise<T> {
  const r = await request(cmd, args);
  if (!r.ok) throw new Error(r.error.message);
  return r.result as T;
}

export function retryNative(): Promise<unknown> {
  return chrome.runtime.sendMessage({ type: 'retryNative' });
}

export function inspector(tabId: number): Promise<InspectorEntry[]> {
  return chrome.runtime.sendMessage({ type: 'inspector', tabId }) as Promise<InspectorEntry[]>;
}

export function clearInspector(tabId: number): Promise<unknown> {
  return chrome.runtime.sendMessage({ type: 'clearInspector', tabId });
}

/** Connects to a server for a while, then back to the one used before. */
export function tryServer(serverId: string, minutes: number): Promise<NativeResponse> {
  return chrome.runtime.sendMessage({ type: 'tryServer', serverId, minutes }) as Promise<NativeResponse>;
}

export function endTrial(): Promise<unknown> {
  return chrome.runtime.sendMessage({ type: 'endTrial' });
}

export function dismissNotices(): Promise<unknown> {
  return chrome.runtime.sendMessage({ type: 'dismissNotices' });
}

/** Subscribes to state and helper events. Returns the port (closing the page closes it). */
export function subscribe(onState: (s: AppState) => void, onEvent: (e: NativeEvent) => void = () => undefined): chrome.runtime.Port {
  const port = chrome.runtime.connect({ name: 'ui' });
  port.onMessage.addListener((m: SwPush) => {
    if (m?.type === 'state') onState(m.state);
    else if (m?.type === 'event') onEvent(m.event);
  });
  return port;
}

/** A backup export; large ones arrive in parts. */
export async function exportBackup(args: Commands['exportBackup']): Promise<{ text: string; fileName: string; encrypted: boolean }> {
  const r = await call<{ text: string | null; fileName: string; encrypted: boolean; token?: number; parts?: number }>('exportBackup', args);
  if (r.text !== null && r.text !== undefined) return { text: r.text, fileName: r.fileName, encrypted: r.encrypted };
  const parts: string[] = [];
  for (let i = 0; i < (r.parts ?? 0); i++) {
    parts.push((await call<{ part: string }>('readExport', { token: r.token ?? 0, index: i })).part);
  }
  return { text: parts.join(''), fileName: r.fileName, encrypted: r.encrypted };
}

export function download(text: string, fileName: string, type = 'application/json') {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

export function openDashboard(section?: string) {
  const url = chrome.runtime.getURL(`dashboard.html${section ? `#${section}` : ''}`);
  void chrome.tabs.create({ url });
}
