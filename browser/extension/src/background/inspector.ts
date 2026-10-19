// Request inspector: which hosts each tab talked to, how often, and whether requests failed.
// Kept in memory only (never stored, never sent anywhere) and cleared when a tab navigates to a
// new page or closes.
import type { InspectorEntry } from '../shared/app-state';

const MAX_TABS = 60;
const MAX_HOSTS = 400;

export class Inspector {
  private tabs = new Map<number, Map<string, InspectorEntry>>();

  private entry(tabId: number, host: string, now: number): InspectorEntry | null {
    if (tabId < 0 || !host) return null;
    let t = this.tabs.get(tabId);
    if (!t) {
      if (this.tabs.size >= MAX_TABS) {
        const oldest = this.tabs.keys().next().value;
        if (oldest !== undefined) this.tabs.delete(oldest);
      }
      t = new Map();
      this.tabs.set(tabId, t);
    }
    let e = t.get(host);
    if (!e) {
      if (t.size >= MAX_HOSTS) return null;
      e = { host, requests: 0, failed: 0, blocked: 0, lastStatus: null, lastError: null, types: [], lastAt: now };
      t.set(host, e);
    }
    return e;
  }

  navigated(tabId: number) {
    this.tabs.delete(tabId);
  }

  closed(tabId: number) {
    this.tabs.delete(tabId);
  }

  completed(tabId: number, host: string, type: string, status: number, now: number) {
    const e = this.entry(tabId, host, now);
    if (!e) return;
    e.requests++;
    e.lastStatus = status;
    e.lastAt = now;
    if (!e.types.includes(type) && e.types.length < 8) e.types.push(type);
  }

  failed(tabId: number, host: string, type: string, error: string, now: number) {
    const e = this.entry(tabId, host, now);
    if (!e) return;
    e.requests++;
    e.lastAt = now;
    // Requests that our kill switch or a Block rule stopped fail at the proxy.
    if (/PROXY|TUNNEL_CONNECTION|ERR_CONNECTION_CLOSED|ERR_EMPTY_RESPONSE/.test(error)) e.blocked++;
    else e.failed++;
    e.lastError = error.replace(/^net::/, '');
    if (!e.types.includes(type) && e.types.length < 8) e.types.push(type);
  }

  list(tabId: number): InspectorEntry[] {
    return [...(this.tabs.get(tabId)?.values() ?? [])].sort((a, b) => b.requests - a.requests);
  }
}
