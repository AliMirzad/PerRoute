// Where a host's traffic goes, computed locally from the helper's rules. It mirrors the routing
// order of the generated Xray config (rules top to bottom, the first match wins, then the routing
// mode), so the popup, the toolbar badge, the request inspector and the kill switch agree without
// a round trip per request.
import type { RoutingMode, RuleAction, SiteRule } from '../../../protocol/types';

export type Outcome = RuleAction;

const LOCAL_SUFFIXES = ['local', 'lan', 'home.arpa', 'internal', 'localdomain', 'home'];
const PRIVATE_V4 = ['10.0.0.0/8', '127.0.0.0/8', '169.254.0.0/16', '172.16.0.0/12', '192.168.0.0/16', '100.64.0.0/10', '0.0.0.0/8'];
const PRIVATE_V6 = ['::1/128', 'fc00::/7', 'fe80::/10'];

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
  const halves = t.split('::');
  if (halves.length > 2) return null;
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
  const head = toGroups(halves[0]);
  const tail = halves.length === 2 ? toGroups(halves[1]) : [];
  if (!head || !tail) return null;
  const fill = halves.length === 2 ? 8 - head.length - tail.length : 0;
  if (fill < 0 || (halves.length === 1 && head.length !== 8)) return null;
  const groups = [...head, ...new Array(fill).fill(0), ...tail];
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

const regexCache = new Map<string, RegExp | null>();
function regex(source: string): RegExp | null {
  let r = regexCache.get(source);
  if (r === undefined) {
    try {
      r = new RegExp(source);
    } catch {
      r = null;
    }
    if (regexCache.size > 500) regexCache.clear();
    regexCache.set(source, r);
  }
  return r;
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
      return regex(value)?.test(host) ?? false;
    default:
      return host === value || host.endsWith(`.${value}`);
  }
}

function portMatches(rule: string, port: number): boolean {
  const [a, b] = rule.split('-').map(Number);
  return b === undefined ? port === a : port >= a && port <= b;
}

export function isIp(host: string): boolean {
  return parseIpv4(host) !== null || parseIpv6(host) !== null;
}

/** Private, loopback and link-local addresses and local names. */
export function isLocal(host: string): boolean {
  const h = host.toLowerCase().replace(/\.$/, '').replace(/^\[|\]$/g, '');
  if (h === 'localhost' || (!h.includes('.') && !isIp(h))) return true;
  if (LOCAL_SUFFIXES.some((s) => h === s || h.endsWith(`.${s}`))) return true;
  if (parseIpv4(h) !== null) return PRIVATE_V4.some((c) => cidrContains(c, h));
  if (parseIpv6(h) !== null) return PRIVATE_V6.some((c) => cidrContains(c, h));
  return false;
}

export const ruleActive = (r: SiteRule, nowSec: number) => r.enabled && (r.expiresAt === null || r.expiresAt > nowSec);

function normalizeHost(host: string): string {
  return host.toLowerCase().replace(/\.$/, '').replace(/^\[|\]$/g, '');
}

/** True if one rule matches this destination. `port` is optional (a rule on ports needs it). */
export function ruleMatches(r: SiteRule, host: string, port?: number): boolean {
  const h = normalizeHost(host);
  const ip = isIp(h);
  if (r.match === 'domain') return !ip && r.values.some((v) => domainMatches(v, h));
  if (r.match === 'ip') return ip && r.values.some((v) => cidrContains(v, h));
  return port !== undefined && r.values.some((v) => portMatches(v, port));
}

/** The first active rule matching `host` (the first match wins, as in Xray). */
export function matchRule(rules: SiteRule[], host: string, nowSec: number, port?: number): SiteRule | null {
  for (const r of rules) {
    if (ruleActive(r, nowSec) && ruleMatches(r, host, port)) return r;
  }
  return null;
}

export function outcomeFor(host: string, rules: SiteRule[], mode: RoutingMode, localNamesDirect: boolean, nowSec: number, port?: number): { outcome: Outcome; rule: SiteRule | null; local: boolean } {
  // Private addresses are in the browser's bypass list: they never reach the tunnel. Local names
  // (printer.local, nas) stay local when the setting is on.
  if (isLocal(host) && (localNamesDirect || isIp(normalizeHost(host)))) return { outcome: 'direct', rule: null, local: true };
  const rule = matchRule(rules, host, nowSec, port);
  if (rule) return { outcome: rule.action, rule, local: false };
  return { outcome: mode === 'selected' ? 'direct' : 'proxy', rule: null, local: false };
}

/** The default port of a URL scheme, for port rules. */
export function portOfUrl(url: string | undefined): number | undefined {
  if (!url) return undefined;
  try {
    const u = new URL(url);
    if (u.port) return Number(u.port);
    return u.protocol === 'https:' || u.protocol === 'wss:' ? 443 : u.protocol === 'http:' || u.protocol === 'ws:' ? 80 : undefined;
  } catch {
    return undefined;
  }
}

/**
 * How the helper names a site in a one-click rule: lowercase, no trailing dot and, for a whole
 * site, no leading "www." (mirrors helper/src/core/routing.rs `host`).
 */
export function siteRuleValue(host: string): string {
  let h = normalizeHost(host).replace(/^\*?\.+/, '');
  if (h.startsWith('www.') && h.slice(4).includes('.')) h = h.slice(4);
  return h;
}

/** The user's own one-click rule for exactly this site, if any (as `evaluate` reports it). */
export function siteRuleFor(rules: SiteRule[], host: string): SiteRule | null {
  const v = siteRuleValue(host);
  return rules.find((r) => !r.scenario && r.match === 'domain' && r.values.length === 1 && r.values[0] === v) ?? null;
}

// ------------------------------------------------------------------ rules that never apply

export interface Shadow {
  /** The rule that can never decide anything. */
  rule: SiteRule;
  /** The earlier rule that decides first. */
  by: SiteRule;
}

/** True if every destination matched by value `v` of kind `m` is also matched by `w`. */
function covers(m: SiteRule['match'], w: string, v: string): boolean {
  if (m === 'ip') {
    const [addr, lenText] = v.split('/');
    const vLen = Number(lenText ?? (addr.includes(':') ? 128 : 32));
    const [, wLenText] = w.split('/');
    const wLen = Number(wLenText ?? (w.includes(':') ? 128 : 32));
    return wLen <= vLen && cidrContains(w, addr);
  }
  if (m === 'port') {
    const [a, b = a] = v.split('-').map(Number);
    const [c, d = c] = w.split('-').map(Number);
    return c <= a && b <= d;
  }
  const kind = (x: string) => (x.includes(':') ? x.slice(0, x.indexOf(':')) : 'domain');
  const value = (x: string) => (x.includes(':') ? x.slice(x.indexOf(':') + 1) : x);
  const kw = kind(w);
  const kv = kind(v);
  if (kw === 'regexp' || kv === 'regexp') return w === v;
  const vw = value(w);
  const vv = value(v);
  if (kw === 'keyword') return vv.includes(vw);
  if (kw === 'full') return kv === 'full' && vv === vw;
  // domain: covers the name and its subdomains.
  if (kv === 'keyword') return false;
  return vv === vw || vv.endsWith(`.${vw}`);
}

/**
 * Rules that can never decide anything because an earlier active rule of the same kind already
 * matches everything they match (the Safety Center's "rules that never apply").
 */
export function shadowedRules(rules: SiteRule[], nowSec: number): Shadow[] {
  const out: Shadow[] = [];
  const active = rules.filter((r) => ruleActive(r, nowSec));
  active.forEach((r, i) => {
    for (const earlier of active.slice(0, i)) {
      if (earlier.match !== r.match) continue;
      if (r.values.every((v) => earlier.values.some((w) => covers(r.match, w, v)))) {
        out.push({ rule: r, by: earlier });
        return;
      }
    }
  });
  return out;
}

// ------------------------------------------------------------------ kill switch

interface PacRule {
  /** "p" tunnel, "d" direct, "b" block. */
  a: 'p' | 'd' | 'b';
  k: 'domain' | 'ip' | 'port';
  v: string[];
}

/**
 * PAC script for the kill switch: while the tunnel is down, every destination that would use the
 * tunnel (or is blocked) goes to a closed local port, so it fails instead of loading without the
 * tunnel. Destinations that a rule, the routing mode or "keep local names local" sends directly
 * keep working. It evaluates the same rules in the same order as Xray; values are normalized by
 * the helper and embedded as JSON data, never as code.
 */
export function killSwitchPac(rules: SiteRule[], mode: RoutingMode, localNamesDirect: boolean, nowSec: number, closedPort: number): string {
  const list: PacRule[] = rules
    .filter((r) => ruleActive(r, nowSec))
    .map((r) => ({ a: r.action === 'proxy' ? 'p' : r.action === 'direct' ? 'd' : 'b', k: r.match, v: r.values.filter((v) => !v.startsWith('regexp:') || regex(v.slice(7)) !== null) }));
  const cfg = { rules: list, selected: mode === 'selected', localDirect: localNamesDirect, local: LOCAL_SUFFIXES, v4: PRIVATE_V4 };
  return [
    `var C = ${JSON.stringify(cfg)};`,
    `var BLOCK = "PROXY 127.0.0.1:${closedPort}";`,
    'function ip4(s) { var m = /^(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)$/.exec(s); return m ? (((+m[1]) * 16777216) + ((+m[2]) << 16) + ((+m[3]) << 8) + (+m[4])) : null; }',
    'function inNet(h, c) { var p = c.split("/"), a = ip4(h), n = ip4(p[0]); if (a === null || n === null) return false; var len = p.length > 1 ? +p[1] : 32; if (len === 0) return true; var size = Math.pow(2, 32 - len); return Math.floor(a / size) === Math.floor(n / size); }',
    'function dom(v, h) { var c = v.indexOf(":"), k = c > 0 ? v.substring(0, c) : "", x = c > 0 ? v.substring(c + 1) : v;',
    '  if (k === "full") return h === x; if (k === "keyword") return h.indexOf(x) >= 0;',
    '  if (k === "regexp") { try { return new RegExp(x).test(h); } catch (e) { return false; } }',
    '  return h === x || h.slice(-(x.length + 1)) === "." + x; }',
    'function port(url) { var m = /^[a-z]+:\\/\\/(?:[^\\/@]*@)?(?:\\[[^\\]]*\\]|[^\\/:]*)(?::(\\d+))?/i.exec(url); if (m && m[1]) return +m[1]; return url.indexOf("https:") === 0 || url.indexOf("wss:") === 0 ? 443 : 80; }',
    'function FindProxyForURL(url, host) {',
    '  host = host.toLowerCase().replace(/\\.$/, "").replace(/^\\[|\\]$/g, "");',
    '  var v4 = ip4(host) !== null, v6 = host.indexOf(":") >= 0, ip = v4 || v6;',
    '  if (host === "localhost" || (!ip && host.indexOf(".") < 0)) return "DIRECT";',
    '  if (v4) { for (var i = 0; i < C.v4.length; i++) if (inNet(host, C.v4[i])) return "DIRECT"; }',
    '  if (v6 && (host === "::1" || /^f[cd]/.test(host) || /^fe[89ab]/.test(host))) return "DIRECT";',
    '  if (C.localDirect && !ip) { for (var j = 0; j < C.local.length; j++) { var s = C.local[j]; if (host === s || host.slice(-(s.length + 1)) === "." + s) return "DIRECT"; } }',
    '  var pt = null;',
    '  for (var r = 0; r < C.rules.length; r++) {',
    '    var R = C.rules[r], hit = false;',
    '    for (var q = 0; q < R.v.length && !hit; q++) {',
    '      var v = R.v[q];',
    '      if (R.k === "domain") hit = !ip && dom(v, host);',
    '      else if (R.k === "ip") hit = v4 && inNet(host, v);',
    '      else { if (pt === null) pt = port(url); var ab = v.split("-"); hit = ab.length > 1 ? pt >= +ab[0] && pt <= +ab[1] : pt === +ab[0]; }',
    '    }',
    '    if (hit) return R.a === "d" ? "DIRECT" : BLOCK;',
    '  }',
    '  return C.selected ? "DIRECT" : BLOCK;',
    '}',
  ].join('\n');
}
