import type { DoctorCheck } from '../../../../protocol/types';
import type { InspectorEntry } from '../../shared/app-state';
import { call, clearInspector, inspector, request } from '../../shared/client';
import { clear, h, toast } from '../../shared/dom';
import { outcomeFor } from '../../shared/rules-eval';
import { hostOfUrl } from '../../shared/site';
import { ctx, isConnected, isReady } from '../context';
import type { Section } from '../section';

interface Safety {
  status: 'ok' | 'warn' | 'fail';
  title: string;
  detail: string;
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
    else out.push({ status: 'warn', title: 'Browser proxy', detail: app?.blocking ? 'The kill switch is holding tunnel sites offline.' : 'Not connected: the browser opens sites directly.' });
  } catch {
    out.push({ status: 'warn', title: 'Browser proxy', detail: 'The proxy setting could not be read.' });
  }
  try {
    const w = await chrome.privacy.network.webRTCIPHandlingPolicy.get({});
    const protectedNow = (w.value as string) === 'disable_non_proxied_udp' || (w.value as string) === 'proxy_only';
    if (s?.webrtcProtection === false) out.push({ status: 'warn', title: 'WebRTC', detail: 'WebRTC protection is off: video calls and some sites can see your real address.' });
    else if (app?.browserProxied && !protectedNow) out.push({ status: 'fail', title: 'WebRTC', detail: 'WebRTC protection is not in effect while connected.' });
    else out.push({ status: 'ok', title: 'WebRTC', detail: app?.browserProxied ? 'WebRTC cannot reveal your real address.' : 'Protection turns on when you connect.' });
  } catch {
    out.push({ status: 'warn', title: 'WebRTC', detail: 'The WebRTC setting could not be read.' });
  }
  out.push({ status: 'ok', title: 'DNS', detail: 'Site names are resolved by the server for tunnel sites, so your network does not see them.' });
  out.push(
    s?.killSwitch
      ? { status: 'ok', title: 'Kill switch', detail: 'If the tunnel drops, tunnel sites stay offline instead of opening directly.' }
      : { status: 'warn', title: 'Kill switch', detail: 'Off: if the tunnel drops, sites open directly until it is back.' },
  );
  out.push(
    s?.localNamesDirect !== false
      ? { status: 'ok', title: 'Local network', detail: 'Local names and private addresses stay on your network.' }
      : { status: 'warn', title: 'Local network', detail: 'Local names are sent through the tunnel and may not open.' },
  );
  return out;
}

function mark(status: string): HTMLElement {
  return h('span', { class: `mark ${status}`, text: status === 'ok' ? '✓' : status === 'warn' ? '!' : '✕' });
}

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

    const doctorCard = h('section', { class: 'card stack' });
    const safetyCard = h('section', { class: 'card stack' });
    const inspectorCard = h('section', { class: 'card stack' });
    const diagCard = h('section', { class: 'card stack' });
    el.append(doctorCard, h('div', { class: 'grid2' }, safetyCard, diagCard), inspectorCard);

    const hostInput = h('input', { type: 'text', placeholder: 'A site that does not open (optional)', style: { flex: '1' } });

    async function runDoctor() {
      running = true;
      renderDoctor();
      try {
        const host = hostOfUrl(hostInput.value.includes('://') ? hostInput.value : `https://${hostInput.value}`) ?? undefined;
        checks = (await call<{ checks: DoctorCheck[] }>('doctor', { host: hostInput.value.trim() ? host : undefined })).checks;
      } catch (e) {
        toast((e as Error).message, 'error');
      }
      running = false;
      renderDoctor();
    }

    async function repair(c: DoctorCheck) {
      try {
        if (c.repair === 'connect' || c.repair === 'reconnect') {
          const id = ctx.app?.status?.serverId ?? ctx.list.selectedServerId;
          if (id) await call('connect', { serverId: id });
        } else if (c.repair === 'switchFastest') {
          toast('Testing servers…');
          await call('connectFastest', {});
        } else if (c.repair === 'addServers') location.hash = 'servers';
        else if (c.repair === 'openRules') location.hash = 'rules';
        toast('Done. Run the doctor again to check.');
      } catch (e) {
        toast((e as Error).message, 'error');
      }
    }

    const REPAIR_LABEL: Record<string, string> = { connect: 'Connect', reconnect: 'Reconnect', switchFastest: 'Switch to the fastest', addServers: 'Add servers', openRules: 'Open rules' };

    function renderDoctor() {
      hostInput.onkeydown = (e) => e.key === 'Enter' && void runDoctor();
      clear(
        doctorCard,
        h('div', { class: 'card-title' }, h('h2', { text: 'Connection doctor' })),
        h('div', { class: 'row' }, hostInput, h('button', { class: 'btn primary', text: running ? 'Checking…' : 'Check', disabled: running || !isReady(), on: { click: () => void runDoctor() } })),
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
                  c.repair ? h('button', { class: 'btn small', text: REPAIR_LABEL[c.repair] ?? 'Fix', on: { click: () => void repair(c) } }) : null,
                ),
              ),
            )
          : h('p', { class: 'muted small', text: 'Checks the runtime, your server, the live route and the exit address, and explains where a site goes.' }),
      );
    }

    async function loadSafety() {
      safety = await safetyChecks();
      clear(
        safetyCard,
        h('h2', { text: 'Safety center' }),
        h('div', null, ...safety.map((c) => h('div', { class: 'check' }, mark(c.status), h('div', { class: 'grow' }, h('b', { text: c.title }), h('div', { class: 'small muted', text: c.detail }))))),
      );
    }

    async function loadDiag() {
      if (!isReady()) return;
      const r = await request('getDiagnostics', {});
      diag = r.ok ? (r.result as Record<string, unknown>) : null;
      const hello = ctx.app?.runtime.kind === 'ready' ? ctx.app.runtime.hello : null;
      const rows: [string, string][] = [
        ['Extension', ctx.app?.extensionVersion ?? ''],
        ['Runtime', hello?.nativeVersion ?? ''],
        ['Xray', String(diag?.xrayVersion ?? 'missing')],
        ['Xray verified', diag?.xrayVerified ? 'yes (pinned SHA-256)' : 'no'],
        ['Credentials', String(hello?.keyStorage ?? '')],
        ['Data folder', String(diag?.dataDir ?? '')],
        ['Folder protection', String(diag?.dataDirProtection ?? '')],
      ];
      clear(
        diagCard,
        h('div', { class: 'card-title' }, h('h2', { text: 'Diagnostics' }), h('button', { class: 'btn small', text: 'Copy', on: { click: () => void copyDiag() } })),
        h('dl', { class: 'kv' }, ...rows.flatMap(([k, v]) => [h('dt', { text: k }), h('dd', { text: v || '—' })])),
      );
    }

    async function copyDiag() {
      const text = JSON.stringify({ extension: ctx.app?.extensionVersion, runtime: ctx.app?.runtime, status: ctx.app?.status, diagnostics: diag }, null, 2);
      await navigator.clipboard.writeText(text);
      toast('Diagnostics copied (no credentials included).');
    }

    async function loadTabs() {
      tabs = (await chrome.tabs.query({})).filter((t) => t.id !== undefined && hostOfUrl(t.url));
      if (tabId === null || !tabs.some((t) => t.id === tabId)) tabId = tabs.find((t) => t.active)?.id ?? tabs[0]?.id ?? null;
      await loadEntries();
    }

    async function loadEntries() {
      entries = tabId === null ? [] : await inspector(tabId);
      renderInspector();
    }

    function renderInspector() {
      const sel = h('select', { style: { maxWidth: '420px' } });
      for (const t of tabs) sel.append(h('option', { value: String(t.id), text: `${t.title || hostOfUrl(t.url)}`.slice(0, 80) }));
      if (tabId !== null) sel.value = String(tabId);
      sel.onchange = () => {
        tabId = Number(sel.value);
        void loadEntries();
      };
      const rules = ctx.rules;
      const now = Math.floor(Date.now() / 1000);
      const table = h('table', { class: 'table' }, h('tr', null, h('th', { text: 'Host' }), h('th', { text: 'Route' }), h('th', { text: 'Requests' }), h('th', { text: 'Problems' }), h('th', { text: 'Rule' })));
      for (const e of entries) {
        const o = rules ? outcomeFor(e.host, rules.rules, rules.mode, rules.localNamesDirect, now) : null;
        const route = !ctx.app?.browserProxied ? (ctx.app?.blocking ? 'block' : 'direct') : (o?.outcome ?? 'proxy');
        table.append(
          h(
            'tr',
            null,
            h('td', { class: 'mono', text: e.host }),
            h('td', null, h('span', { class: `chip ${route}`, text: route === 'proxy' ? 'Tunnel' : route === 'direct' ? 'Direct' : 'Blocked' })),
            h('td', { text: String(e.requests) }),
            h('td', null, e.failed || e.blocked ? h('span', { class: 'chip fail', title: e.lastError ?? '', text: `${e.failed + e.blocked} failed` }) : h('span', { class: 'muted small', text: '—' })),
            h('td', { class: 'muted small', text: o?.rule ? o.rule.name : ctx.app?.browserProxied ? (rules?.mode === 'selected' ? 'Not selected' : 'Default') : '' }),
          ),
        );
      }
      clear(
        inspectorCard,
        h(
          'div',
          { class: 'toolbar' },
          h('h2', { class: 'grow', text: 'Request inspector' }),
          sel,
          h('button', { class: 'btn small', text: 'Refresh', on: { click: () => void loadTabs() } }),
          h('button', { class: 'btn small', text: 'Clear', on: { click: () => tabId !== null && void clearInspector(tabId).then(loadEntries) } }),
        ),
        h('p', { class: 'muted small', text: `Every host the tab contacted since it last loaded a page. Kept only in memory. ${isConnected() ? '' : 'Not connected: everything opens directly.'}` }),
        entries.length ? h('div', { class: 'table-wrap' }, table) : h('div', { class: 'empty', text: tabs.length ? 'No requests recorded for this tab yet. Reload it to see them.' : 'Open a website in another tab.' }),
      );
    }

    renderDoctor();
    void loadSafety();
    void loadDiag();
    void loadTabs();
    const timer = setInterval(() => void loadEntries(), 4000);
    return {
      update() {
        void loadSafety();
        if (!diag) void loadDiag();
      },
      unmount: () => clearInterval(timer),
    };
  },
};
