import type { DoctorCheck, RuleAction } from '../../../../protocol/types';
import type { InspectorEntry } from '../../shared/app-state';
import { call, clearInspector, inspector, request } from '../../shared/client';
import { actionButton, copyText, h, menuButton, region, select, toast } from '../../shared/dom';
import { outcomeFor, shadowedRules } from '../../shared/rules-eval';
import { hostOfUrl, siteOf } from '../../shared/site';
import { ctx, isConnected, isReady, refreshRules } from '../context';
import type { Section } from '../section';

interface Safety {
  status: 'ok' | 'warn' | 'fail';
  title: string;
  detail: string;
  fix?: { label: string; href: string };
}

async function safetyChecks(): Promise<Safety[]> {
  const out: Safety[] = [];
  const app = ctx.app;
  const s = app?.settings;
  try {
    const p = await chrome.proxy.settings.get({ incognito: false });
    if (p.levelOfControl === 'controlled_by_other_extensions') out.push({ status: 'fail', title: 'Browser proxy', detail: 'Another extension controls the browser proxy. Disable it so PerRoute can route this browser.' });
    else if (p.levelOfControl === 'not_controllable') out.push({ status: 'fail', title: 'Browser proxy', detail: 'A browser policy controls the proxy settings, so PerRoute cannot route this browser.' });
    else if (app?.browserProxied) out.push({ status: 'ok', title: 'Browser proxy', detail: 'This browser is routed through PerRoute.' });
    else out.push({ status: 'warn', title: 'Browser proxy', detail: app?.blocking ? 'The kill switch is holding tunnel sites offline.' : 'Not connected: the browser opens sites directly.', fix: app?.blocking ? undefined : { label: 'Connect', href: '#connect' } });
  } catch {
    out.push({ status: 'warn', title: 'Browser proxy', detail: 'The proxy setting could not be read.' });
  }
  try {
    const w = await chrome.privacy.network.webRTCIPHandlingPolicy.get({});
    const protectedNow = (w.value as string) === 'disable_non_proxied_udp' || (w.value as string) === 'proxy_only';
    if (s?.webrtcProtection === false) out.push({ status: 'warn', title: 'WebRTC', detail: 'WebRTC protection is off: video calls and some sites can see your real address.', fix: { label: 'Settings', href: '#settings' } });
    else if (app?.browserProxied && !protectedNow) out.push({ status: 'fail', title: 'WebRTC', detail: 'WebRTC protection is not in effect while connected.' });
    else out.push({ status: 'ok', title: 'WebRTC', detail: app?.browserProxied ? 'WebRTC cannot reveal your real address.' : 'Protection turns on when you connect.' });
  } catch {
    out.push({ status: 'warn', title: 'WebRTC', detail: 'The WebRTC setting could not be read.' });
  }
  out.push({ status: 'ok', title: 'Name lookups (DNS)', detail: 'Sites that use the tunnel are looked up by the server, so your network does not see their names. Direct sites use your normal DNS.' });
  out.push(
    s?.killSwitch
      ? { status: 'ok', title: 'Kill switch', detail: 'If the tunnel drops, tunnel sites stay offline; sites your rules send directly keep working.' }
      : { status: 'warn', title: 'Kill switch', detail: 'Off: if the tunnel drops, sites open directly until it is back.', fix: { label: 'Settings', href: '#settings' } },
  );
  out.push(
    ctx.rules?.localNamesDirect !== false
      ? { status: 'ok', title: 'Local network', detail: 'Local names and private addresses stay on your network.' }
      : { status: 'warn', title: 'Local network', detail: 'Local names are sent through the tunnel and may not open.', fix: { label: 'Rules', href: '#rules' } },
  );
  const rules = ctx.rules?.rules ?? [];
  const shadows = shadowedRules(rules, Math.floor(Date.now() / 1000));
  out.push(
    shadows.length
      ? { status: 'warn', title: 'Rules', detail: `${shadows.map((x) => `“${x.rule.name}”`).slice(0, 3).join(', ')}${shadows.length > 3 ? ` and ${shadows.length - 3} more` : ''} never ${shadows.length === 1 ? 'applies' : 'apply'}: a rule above decides first.`, fix: { label: 'Rules', href: '#rules' } }
      : { status: 'ok', title: 'Rules', detail: rules.length ? 'Every rule can apply: none is hidden behind an earlier one.' : 'No rules: the routing mode decides.' },
  );
  const ids = new Set(ctx.list.servers.map((x) => x.id));
  const orphans = rules.filter((r) => r.serverId && !ids.has(r.serverId));
  if (orphans.length) out.push({ status: 'warn', title: 'Rule servers', detail: `${orphans.map((r) => `“${r.name}”`).join(', ')} ${orphans.length === 1 ? 'uses a server that was' : 'use servers that were'} removed; the main server is used instead.`, fix: { label: 'Rules', href: '#rules' } });
  return out;
}

function mark(status: string): HTMLElement {
  return h('span', { class: `mark ${status}`, ariaLabel: status === 'ok' ? 'OK' : status === 'warn' ? 'Warning' : 'Problem', text: status === 'ok' ? '✓' : status === 'warn' ? '!' : '✕' });
}

const ROUTE_TEXT: Record<string, string> = { proxy: 'Tunnel', direct: 'Direct', block: 'Blocked' };

export const doctorSection: Section = {
  id: 'doctor',
  title: 'Doctor',
  subtitle: 'Why something does not open, what protects you, and what each tab connects to.',
  icon: 'doctor',
  mount(el) {
    let checks: DoctorCheck[] | null = null;
    let running = false;
    let safety: Safety[] = [];
    let tabId: number | null = null;
    let tabs: chrome.tabs.Tab[] = [];
    let entries: InspectorEntry[] = [];
    let diag: Record<string, unknown> | null = null;

    const hostInput = h('input', { type: 'text', placeholder: 'A site that does not open (optional)', class: 'grow', ariaLabel: 'Site to check' });
    hostInput.onkeydown = (e) => e.key === 'Enter' && void runDoctor();

    async function runDoctor() {
      running = true;
      doctorCard.render(true);
      try {
        const raw = hostInput.value.trim();
        const host = raw ? (hostOfUrl(raw.includes('://') ? raw : `https://${raw}`) ?? undefined) : undefined;
        if (raw && !host) throw new Error('That is not a site or address.');
        checks = (await call<{ checks: DoctorCheck[] }>('doctor', { host })).checks;
      } catch (e) {
        toast((e as Error).message, 'error');
      }
      running = false;
      doctorCard.render(true);
    }

    async function repair(c: DoctorCheck) {
      if (c.repair === 'addServers') return void (location.hash = 'servers');
      if (c.repair === 'openRules') return void (location.hash = 'rules');
      if (c.repair === 'connect' || c.repair === 'reconnect') {
        const id = ctx.app?.status?.serverId ?? ctx.list.selectedServerId;
        if (!id) throw new Error('Choose a server first.');
        await call('connect', { serverId: id });
      } else if (c.repair === 'switchFastest') {
        toast('Testing servers…');
        await call('connectFastest', {});
      }
      toast('Done. Check again in a moment.');
    }

    const REPAIR_LABEL: Record<string, string> = { connect: 'Connect', reconnect: 'Reconnect', switchFastest: 'Switch to the fastest', addServers: 'Add servers', openRules: 'Open rules' };
    const summary = () => {
      if (!checks) return null;
      const fail = checks.filter((c) => c.status === 'fail').length;
      const warn = checks.filter((c) => c.status === 'warn').length;
      const first = checks.find((c) => c.status === 'fail') ?? checks.find((c) => c.status === 'warn');
      return h('div', { class: `banner ${fail ? 'error' : warn ? '' : 'info'}` }, mark(fail ? 'fail' : warn ? 'warn' : 'ok'), h('div', { class: 'grow small' }, h('b', { text: fail ? 'Most likely cause' : warn ? 'Worth a look' : 'Everything checked is working' }), first ? h('div', { text: `${first.title}: ${first.detail}` }) : null));
    };

    const doctorCard = region(h('section', { class: 'card stack' }), () => [
      h('div', { class: 'card-title' }, h('h2', { text: 'Connection doctor' })),
      h('p', { class: 'muted small', text: 'Checks the runtime, your server, the live route and the exit address, from the outside in, and explains where a site goes.' }),
      h('div', { class: 'row' }, hostInput, h('button', { class: 'btn primary', text: running ? 'Checking…' : 'Check', disabled: running || !isReady(), on: { click: () => void runDoctor() } })),
      summary(),
      checks
        ? h(
            'div',
            null,
            ...checks.map((c) =>
              h(
                'div',
                { class: 'check' },
                mark(c.status),
                h('div', { class: 'grow' }, h('b', { text: c.title }), h('div', { class: 'small muted', text: c.detail })),
                c.repair ? actionButton(REPAIR_LABEL[c.repair] ?? 'Fix', 'btn small', () => repair(c), 'Working…') : null,
              ),
            ),
          )
        : null,
    ], () => [checks, running, isReady()]);

    const safetyCard = region(h('section', { class: 'card stack' }), () => [
      h('div', { class: 'card-title' }, h('h2', { text: 'Safety center' }), h('span', { class: 'muted small', text: safety.every((s) => s.status === 'ok') && safety.length ? 'All good' : `${safety.filter((s) => s.status !== 'ok').length} to look at` })),
      h('div', null, ...safety.map((c) => h('div', { class: 'check' }, mark(c.status), h('div', { class: 'grow' }, h('b', { text: c.title }), h('div', { class: 'small muted', text: c.detail })), c.fix ? h('a', { class: 'btn small', text: c.fix.label, attrs: { href: c.fix.href } }) : null))),
    ], () => safety);

    async function loadSafety() {
      safety = await safetyChecks();
      safetyCard.render();
    }

    const diagCard = region(h('section', { class: 'card stack' }), () => {
      const hello = ctx.app?.runtime.kind === 'ready' ? ctx.app.runtime.hello : null;
      const rows: [string, string][] = [
        ['Extension', ctx.app?.extensionVersion ?? ''],
        ['Runtime', hello?.nativeVersion ?? (isReady() ? '' : 'not available')],
        ['Xray', String(diag?.xrayVersion ?? (hello?.xrayVersion || 'missing'))],
        ['Xray verified', diag ? (diag.xrayVerified ? 'Yes (pinned SHA-256)' : 'No') : ''],
        ['Credentials', String(hello?.keyStorage ?? '')],
        ['Data folder', String(diag?.dataDir ?? '')],
        ['Folder protection', String(diag?.dataDirProtection ?? '')],
      ];
      return [
        h(
          'div',
          { class: 'card-title' },
          h('h2', { text: 'Diagnostics' }),
          h('button', {
            class: 'btn small',
            text: 'Copy',
            on: {
              click: () => void copyText(JSON.stringify({ extension: ctx.app?.extensionVersion, runtime: ctx.app?.runtime, status: ctx.app?.status, diagnostics: diag }, null, 2), 'Diagnostics (no credentials)'),
            },
          }),
        ),
        h('dl', { class: 'kv' }, ...rows.flatMap(([k, v]) => [h('dt', { text: k }), h('dd', { text: v || '—' })])),
      ];
    }, () => [diag, ctx.app?.runtime, ctx.app?.extensionVersion]);

    async function loadDiag() {
      if (!isReady()) return diagCard.render();
      const r = await request('getDiagnostics', {});
      diag = r.ok ? (r.result as Record<string, unknown>) : null;
      diagCard.render();
    }

    async function routeHost(host: string, action: RuleAction | null) {
      const r = await request('setSite', { host, action, serverId: null, minutes: null });
      if (!r.ok) throw new Error(r.error.message);
      toast(`${host} ${action === null ? 'follows your rules again' : action === 'proxy' ? 'uses the tunnel' : action === 'direct' ? 'opens directly' : 'is blocked'}.`);
      await refreshRules();
    }

    const inspectorCard = region(h('section', { class: 'card stack' }), () => {
      const rules = ctx.rules;
      const now = Math.floor(Date.now() / 1000);
      const tabOpts: [string, string][] = tabs.map((t) => [String(t.id), `${t.title || hostOfUrl(t.url)}`.slice(0, 80)]);
      const tbody = h('tbody');
      for (const e of entries) {
        const o = rules ? outcomeFor(e.host, rules.rules, rules.mode, rules.localNamesDirect, now) : null;
        const route = ctx.app?.browserProxied ? (o?.outcome ?? 'proxy') : ctx.app?.blocking ? (o?.outcome === 'direct' ? 'direct' : 'block') : 'direct';
        const problems = e.failed + e.blocked;
        tbody.append(
          h(
            'tr',
            null,
            h('td', { class: 'mono ellipsis host-cell', title: e.host, text: e.host }),
            h('td', null, h('span', { class: `chip ${route}`, text: ROUTE_TEXT[route] })),
            h('td', { class: 'num', text: String(e.requests) }),
            h('td', null, problems ? h('span', { class: 'chip fail', title: e.lastError ?? '', text: `${problems} failed` }) : h('span', { class: 'muted small', text: '—' })),
            h('td', { class: 'muted small ellipsis', text: o?.local ? 'Local' : o?.rule ? o.rule.name : ctx.app?.browserProxied ? (rules?.mode === 'selected' ? 'Not selected' : 'Default') : '' }),
            h(
              'td',
              null,
              menuButton(() => {
                const site = siteOf(e.host);
                return [
                  { label: `Tunnel for ${site}`, run: () => routeHost(site, 'proxy') },
                  { label: `Direct for ${site}`, run: () => routeHost(site, 'direct') },
                  { label: `Block ${site}`, danger: true, run: () => routeHost(site, 'block') },
                  { label: `Let my rules decide ${site}`, separated: true, run: () => routeHost(site, null) },
                  { label: 'Copy host', separated: true, run: () => copyText(e.host, 'Host') },
                ];
              }, { label: 'Route this host' }),
            ),
          ),
        );
      }
      return [
        h(
          'div',
          { class: 'toolbar' },
          h('h2', { class: 'grow', text: 'Request inspector' }),
          tabOpts.length ? select(tabOpts, String(tabId ?? ''), (v) => ((tabId = Number(v)), void loadEntries()), { ariaLabel: 'Tab', style: { maxWidth: '360px' } }) : null,
          h('button', { class: 'btn small', text: 'Refresh', on: { click: () => void loadTabs() } }),
          h('button', { class: 'btn small', text: 'Clear', disabled: tabId === null, on: { click: () => tabId !== null && void clearInspector(tabId).then(loadEntries) } }),
        ),
        h('p', { class: 'muted small', text: `Every host the tab contacted since it last loaded a page, kept only in memory. ${isConnected() ? '' : 'Not connected: everything opens directly.'}` }),
        entries.length
          ? h('div', { class: 'table-wrap', data: { scroll: 'inspector' } }, h('table', { class: 'table' }, h('thead', null, h('tr', null, h('th', { text: 'Host' }), h('th', { text: 'Route' }), h('th', { text: 'Requests' }), h('th', { text: 'Problems' }), h('th', { text: 'Rule' }), h('th'))), tbody))
          : h('div', { class: 'empty', text: tabs.length ? 'No requests recorded for this tab yet. Reload it to see them.' : 'Open a website in another tab.' }),
      ];
    }, () => [tabs.map((t) => [t.id, t.title, t.url]), tabId, entries, ctx.rules, ctx.app?.browserProxied, ctx.app?.blocking]);

    async function loadTabs() {
      tabs = (await chrome.tabs.query({})).filter((t) => t.id !== undefined && hostOfUrl(t.url));
      if (tabId === null || !tabs.some((t) => t.id === tabId)) tabId = tabs.find((t) => t.active)?.id ?? tabs[0]?.id ?? null;
      await loadEntries();
    }

    async function loadEntries() {
      entries = tabId === null ? [] : await inspector(tabId);
      inspectorCard.render();
    }

    el.append(doctorCard.el, h('div', { class: 'grid2' }, safetyCard.el, diagCard.el), inspectorCard.el);
    doctorCard.render();
    void loadSafety();
    void loadDiag();
    void loadTabs();
    const timer = setInterval(() => void loadEntries(), 4000);
    return {
      update() {
        doctorCard.render();
        void loadSafety();
        if (!diag) void loadDiag();
        else diagCard.render();
        inspectorCard.render();
      },
      unmount: () => clearInterval(timer),
    };
  },
};
