import { describe, expect, it } from 'vitest';
import type { SiteRule } from '../../protocol/types';
import { Inspector } from '../src/background/inspector';
import { decodeRecipe, encodeRecipe } from '../src/shared/recipe';
import { isLocal, matchRule, outcomeFor } from '../src/shared/rules-eval';
import { hostOfUrl, siteOf } from '../src/shared/site';

const rule = (values: string[], action: SiteRule['action'], extra: Partial<SiteRule> = {}): SiteRule => ({
  id: Math.random().toString(),
  name: values[0],
  match: 'domain',
  values,
  action,
  serverId: null,
  enabled: true,
  expiresAt: null,
  scenario: null,
  createdAt: 0,
  active: true,
  ...extra,
});

describe('local rule evaluation mirrors the helper', () => {
  const rules = [
    rule(['youtube.com'], 'proxy'),
    rule(['full:api.example.com', 'keyword:ads'], 'block'),
    rule(['10.0.0.0/8', '2001:db8::/32'], 'direct', { match: 'ip' }),
    rule(['example.com'], 'direct'),
  ];

  it('first match wins, with domain, full and keyword forms', () => {
    expect(matchRule(rules, 'm.youtube.com', 0)?.action).toBe('proxy');
    expect(matchRule(rules, 'notyoutube.com', 0)).toBeNull();
    expect(matchRule(rules, 'api.example.com', 0)?.action).toBe('block');
    expect(matchRule(rules, 'x.api.example.com', 0)?.action).toBe('direct');
    expect(matchRule(rules, 'myads.net', 0)?.action).toBe('block');
  });

  it('matches IP rules only for IP addresses', () => {
    expect(matchRule(rules, '10.1.2.3', 0)?.action).toBe('direct');
    expect(matchRule(rules, '[2001:db8::5]', 0)?.action).toBe('direct');
    expect(matchRule(rules, '11.1.2.3', 0)).toBeNull();
  });

  it('skips disabled and expired rules', () => {
    const r = [rule(['a.com'], 'block', { enabled: false }), rule(['b.com'], 'block', { expiresAt: 100 })];
    expect(matchRule(r, 'a.com', 0)).toBeNull();
    expect(matchRule(r, 'b.com', 99)?.action).toBe('block');
    expect(matchRule(r, 'b.com', 100)).toBeNull();
  });

  it('routing mode and local names decide the rest', () => {
    expect(outcomeFor('news.example.org', rules, 'all', true, 0).outcome).toBe('proxy');
    expect(outcomeFor('news.example.org', rules, 'selected', true, 0).outcome).toBe('direct');
    expect(outcomeFor('www.youtube.com', rules, 'selected', true, 0).outcome).toBe('proxy');
    expect(outcomeFor('printer.local', rules, 'all', true, 0).outcome).toBe('direct');
    expect(outcomeFor('192.168.1.1', rules, 'all', false, 0).outcome).toBe('direct');
    expect(isLocal('nas')).toBe(true);
    expect(isLocal('8.8.8.8')).toBe(false);
    expect(isLocal('fe80::1')).toBe(true);
  });
});

describe('site names', () => {
  it('finds the site a host belongs to', () => {
    expect(siteOf('m.youtube.com')).toBe('youtube.com');
    expect(siteOf('www.bbc.co.uk')).toBe('bbc.co.uk');
    expect(siteOf('user.github.io')).toBe('user.github.io');
    expect(siteOf('example.com')).toBe('example.com');
    expect(siteOf('10.0.0.1')).toBe('10.0.0.1');
    expect(siteOf('digikala.co.ir')).toBe('digikala.co.ir');
  });
  it('only websites have a host', () => {
    expect(hostOfUrl('https://www.example.com/x?y')).toBe('www.example.com');
    expect(hostOfUrl('http://[::1]:8080/')).toBe('::1');
    expect(hostOfUrl('chrome://extensions')).toBeNull();
    expect(hostOfUrl('file:///C:/x.txt')).toBeNull();
    expect(hostOfUrl(undefined)).toBeNull();
  });
});

describe('recipes', () => {
  it('round-trip without servers or per-rule server choices', () => {
    const text = encodeRecipe('Mine', 'selected', [rule(['youtube.com', 'googlevideo.com'], 'proxy', { serverId: 'secret-server-id' }), rule(['bank.example'], 'direct')]);
    expect(text.startsWith('perroute-recipe:')).toBe(true);
    expect(text).not.toContain('secret');
    const r = decodeRecipe(text);
    expect(r.name).toBe('Mine');
    expect(r.mode).toBe('selected');
    expect(r.rules).toEqual([
      { name: 'youtube.com', match: 'domain', text: 'youtube.com, googlevideo.com', action: 'proxy' },
      { name: 'bank.example', match: 'domain', text: 'bank.example', action: 'direct' },
    ]);
    expect(JSON.stringify(r)).not.toContain('serverId');
  });
  it('rejects anything else', () => {
    expect(() => decodeRecipe('vless://x')).toThrow(/not a PerRoute recipe/);
    expect(() => decodeRecipe('perroute-recipe:!!!')).toThrow();
    const bad = 'perroute-recipe:' + btoa(JSON.stringify({ v: 1, name: 'x', mode: 'all', rules: [{ name: 'x', match: 'exec', text: 'a', action: 'proxy' }] }));
    expect(() => decodeRecipe(bad)).toThrow(/invalid rule/);
  });
});

describe('request inspector', () => {
  it('counts per host, separates failures and resets on navigation', () => {
    const i = new Inspector();
    i.completed(1, 'a.com', 'script', 200, 1);
    i.completed(1, 'a.com', 'image', 200, 2);
    i.failed(1, 'b.com', 'xmlhttprequest', 'net::ERR_PROXY_CONNECTION_FAILED', 3);
    i.failed(1, 'c.com', 'script', 'net::ERR_NAME_NOT_RESOLVED', 4);
    const list = i.list(1);
    expect(list[0]).toMatchObject({ host: 'a.com', requests: 2, failed: 0, types: ['script', 'image'] });
    expect(list.find((e) => e.host === 'b.com')).toMatchObject({ blocked: 1, failed: 0 });
    expect(list.find((e) => e.host === 'c.com')).toMatchObject({ blocked: 0, failed: 1, lastError: 'ERR_NAME_NOT_RESOLVED' });
    expect(i.list(2)).toEqual([]);
    i.navigated(1);
    expect(i.list(1)).toEqual([]);
    i.completed(-1, 'x.com', 'other', 200, 5);
    expect(i.list(-1)).toEqual([]);
  });
});
