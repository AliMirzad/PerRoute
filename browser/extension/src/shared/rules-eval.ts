// Where a host's traffic goes, computed locally from the helper's rules. It mirrors
// helper/src/core/routing.rs `evaluate` and the routing order of the generated Xray config, so the
// toolbar badge and the request inspector need no round trip per request.
import type { RoutingMode, RuleAction, SiteRule } from '../../../protocol/types';

export type Outcome = RuleAction;

const LOCAL_SUFFIXES = ['local', 'lan', 'home.arpa', 'internal', 'localdomain', 'home'];

function parseIpv4(s: string): number | null {
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(s);
  if (!m) return null;
  const parts = m.slice(1).map(Number);
  if (parts.some((p) => p > 255)) return null;
  return ((parts[0] << 24) | (parts[1] << 16) | (parts[2] << 8) | parts[3]) >>> 0;
}

/** IPv6 to a 128-bit BigInt (no zone ids). */
function parseIpv6(s: string): bigint | null {
  const t = s.replace(/^\[|\]$/g, '');
  if (!t.includes(':') || !/^[0-9a-fA-F:.]+$/.test(t)) return null;
  let [head, tail] = t.split('::');
  if (t.split('::').length > 2) return null;
  const toGroups = (x: string | undefined): number[] | null => {
    if (!x) return [];
    const out: number[] = [];
    for (const g of x.split(':')) {
      if (g.includes('.')) {
        const v4 = parseIpv4(g);
        if (v4 === null) return null;
        out.push(v4 >>> 16, v4 & 0xffff);
      } else {
        if (!/^[0-9a-fA-F]{1,4}$/.test(g)) return null;
        out.push(parseInt(g, 16));
      }
    }
    return out;
  };
  const h = toGroups(head);
  const tl = tail === undefined ? [] : toGroups(tail);
  if (!h || !tl) return null;
  const fill = tail === undefined ? 0 : 8 - h.length - tl.length;
  if (fill < 0 || (tail === undefined && h.length !== 8)) return null;
  const groups = [...h, ...new Array(fill).fill(0), ...tl];
  return groups.reduce((acc, g) => (acc << 16n) | BigInt(g), 0n);
}

function cidrContains(rule: string, host: string): boolean {
  const [addr, lenText] = rule.split('/');
  const v4 = parseIpv4(host);
  const n4 = parseIpv4(addr);
  if (v4 !== null && n4 !== null) {
    const len = lenText === undefined ? 32 : Number(lenText);
    const mask = len === 0 ? 0 : (0xffffffff << (32 - len)) >>> 0;
    return (v4 & mask) >>> 0 === (n4 & mask) >>> 0;
  }
  const v6 = parseIpv6(host);
  const n6 = parseIpv6(addr);
  if (v6 !== null && n6 !== null) {
    const len = BigInt(lenText === undefined ? 128 : Number(lenText));
    const all = (1n << 128n) - 1n;
    const mask = len === 0n ? 0n : (all << (128n - len)) & all;
    return (v6 & mask) === (n6 & mask);
  }
  return false;
}

function domainMatches(rule: string, host: string): boolean {
  const i = rule.indexOf(':');
  const kind = i > 0 ? rule.slice(0, i) : '';
  const value = i > 0 ? rule.slice(i + 1) : rule;
  switch (kind) {
    case 'full':
      return host === value;
    case 'keyword':
      return host.includes(value);
    case 'regexp':
      return false;
    default:
      return host === value || host.endsWith(`.${value}`);
  }
}

export function isIp(host: string): boolean {
  return parseIpv4(host) !== null || parseIpv6(host) !== null;
}

/** Private, loopback and link-local addresses and local names. */
export function isLocal(host: string): boolean {
  const h = host.toLowerCase().replace(/\.$/, '');
  if (h === 'localhost' || (!h.includes('.') && !isIp(h))) return true;
  if (LOCAL_SUFFIXES.some((s) => h === s || h.endsWith(`.${s}`))) return true;
  const v4 = parseIpv4(h);
  if (v4 !== null) {
    return ['10.0.0.0/8', '127.0.0.0/8', '169.254.0.0/16', '172.16.0.0/12', '192.168.0.0/16', '100.64.0.0/10', '0.0.0.0/8'].some((c) => cidrContains(c, h));
  }
  if (parseIpv6(h) !== null) return ['::1/128', 'fc00::/7', 'fe80::/10'].some((c) => cidrContains(c, h));
  return false;
}

/** The first active rule matching `host` (the first match wins, as in Xray). */
export function matchRule(rules: SiteRule[], host: string, nowSec: number): SiteRule | null {
  const h = host.toLowerCase().replace(/\.$/, '').replace(/^\[|\]$/g, '');
  const ip = isIp(h);
  for (const r of rules) {
    if (!r.enabled || (r.expiresAt !== null && r.expiresAt <= nowSec)) continue;
    if (r.match === 'domain' && !ip && r.values.some((v) => domainMatches(v, h))) return r;
    if (r.match === 'ip' && ip && r.values.some((v) => cidrContains(v, h))) return r;
  }
  return null;
}

export function outcomeFor(host: string, rules: SiteRule[], mode: RoutingMode, localNamesDirect: boolean, nowSec: number): { outcome: Outcome; rule: SiteRule | null } {
  if (isLocal(host) && (localNamesDirect || isIp(host))) return { outcome: 'direct', rule: null };
  const rule = matchRule(rules, host, nowSec);
  if (rule) return { outcome: rule.action, rule };
  return { outcome: mode === 'selected' ? 'direct' : 'proxy', rule: null };
}

/**
 * PAC script for the kill switch in "selected sites" mode: only the sites that should use the
 * tunnel are sent to a closed local port (so they fail instead of going direct); the rest goes
 * direct as usual. Values are normalized names from the helper and embedded as JSON data.
 */
export function killSwitchPac(rules: SiteRule[], nowSec: number, closedPort: number): string {
  const domains: string[] = [];
  for (const r of rules) {
    if (r.match !== 'domain' || r.action !== 'proxy' || !r.enabled || (r.expiresAt !== null && r.expiresAt <= nowSec)) continue;
    for (const v of r.values) if (!v.startsWith('regexp:')) domains.push(v);
  }
  const data = JSON.stringify(domains);
  return [
    'var D = ' + data + ';',
    'function FindProxyForURL(url, host) {',
    '  host = host.toLowerCase();',
    '  for (var i = 0; i < D.length; i++) {',
    '    var d = D[i], k = "", v = d, c = d.indexOf(":");',
    '    if (c > 0) { k = d.substring(0, c); v = d.substring(c + 1); }',
    '    if (k === "full" ? host === v : k === "keyword" ? host.indexOf(v) >= 0 : (host === v || host.slice(-(v.length + 1)) === "." + v))',
    `      return "PROXY 127.0.0.1:${closedPort}";`,
    '  }',
    '  return "DIRECT";',
    '}',
  ].join('\n');
}
