import type { ExitInfo, QualityScore, Settings, Traffic } from '../../../../protocol/types';
import { call, request, retryNative } from '../../shared/client';
import { clear, h, toast, toggle } from '../../shared/dom';
import { bytes, mbps, rate } from '../../shared/format';
import { currentServerId, deriveView, protocolLine } from '../../shared/view';
import { activeServerId, ctx, isConnected, isReady, sortedServers } from '../context';
import type { Section } from '../section';

export const connectSection: Section = {
  id: 'connect',
  title: 'Connection',
  subtitle: 'Connect, choose a server and see how the route is doing.',
  icon: 'power',
  mount(el) {
    let busy = false;
    let exit: ExitInfo | null = null;
    let exitError = '';
    let speed: string | null = null;
    let quality: QualityScore | null = null;
    let qualityFor: string | null = null;
    let traffic: Traffic | null = null;

    const hero = h('section', { class: 'card hero' });
    const cards = h('div', { class: 'grid3' });
    const exitCard = h('section', { class: 'card stack' });
    const liveCard = h('section', { class: 'card stack' });
    const qualityCard = h('section', { class: 'card stack' });
    const protectCard = h('section', { class: 'card stack' });
    const localCard = h('section', { class: 'card stack' });
    cards.append(exitCard, liveCard, qualityCard);
    const lower = h('div', { class: 'grid2' }, protectCard, localCard);
    el.append(hero, cards, lower);

    async function connectTo(id: string) {
      busy = true;
      update();
      const r = await request('connect', { serverId: id });
      busy = false;
      if (!r.ok) toast(r.error.message, 'error');
      update();
    }

    async function fastest() {
      busy = true;
      update();
      toast('Testing the best servers…');
      try {
        const r = await call<{ serverId: string; ms: number }>('connectFastest', {});
        toast(`Connecting to the fastest server (${r.ms} ms).`);
      } catch (e) {
        toast((e as Error).message, 'error');
      }
      busy = false;
      update();
    }

    async function loadQuality() {
      const id = activeServerId() ?? ctx.list.selectedServerId;
      if (!id || !isReady()) {
        quality = null;
        return;
      }
      qualityFor = id;
      const r = await request('getQuality', { id });
      quality = r.ok ? (r.result as QualityScore) : null;
      renderQuality();
    }

    async function loadTraffic() {
      if (!isReady()) return;
      const r = await request('getTraffic', {});
      traffic = r.ok ? (r.result as Traffic) : null;
      renderLive();
    }

    function renderHero() {
      const app = ctx.app;
      if (!app) return;
      const v = deriveView(app);
      const current = currentServerId(app, ctx.list.selectedServerId);
      const server = ctx.list.servers.find((s) => s.id === current);
      const st = app.status?.state;
      const locked = busy || st === 'connecting' || st === 'disconnecting' || !isReady();
      const sel = h('select', { ariaLabel: 'Server', disabled: locked || ctx.list.servers.length === 0, style: { minWidth: '260px' } });
      for (const s of sortedServers()) sel.append(h('option', { value: s.id, text: `${ctx.list.favorites?.includes(s.id) ? '★ ' : ''}${s.name}` }));
      sel.value = current ?? '';
      sel.onchange = async () => {
        await request('selectServer', { id: sel.value });
        ctx.list.selectedServerId = sel.value;
        if (isConnected()) await connectTo(sel.value);
        else update();
      };
      const primary = h('button', { class: `btn big ${v.action === 'connect' && !app.blocking ? 'primary' : ''}`, style: { minWidth: '150px' } });
      primary.textContent = app.blocking ? 'Disconnect' : v.action === 'connect' ? 'Connect' : v.action === 'disconnect' ? 'Disconnect' : v.action === 'cancel' ? 'Cancel' : v.action === 'retry' ? 'Retry' : 'Please wait…';
      primary.disabled = busy || v.action === 'none' || (v.action === 'connect' && !current && !app.blocking);
      primary.onclick = async () => {
        if (v.action === 'retry') return void retryNative();
        if (v.action === 'connect' && !app.blocking) {
          if (app.settings?.fastestOnConnect && ctx.list.servers.length > 1) return void fastest();
          if (current) return void connectTo(current);
          return;
        }
        busy = true;
        update();
        const r = await request('disconnect', {});
        busy = false;
        if (!r.ok) toast(r.error.message, 'error');
        update();
      };
      hero.dataset.tone = app.blocking ? 'error' : v.tone;
      const detail = app.blocking
        ? 'The tunnel is down and the kill switch keeps tunnel sites offline. Disconnect to browse without it.'
        : (v.detail ?? (server ? `${protocolLine(server)} · ${server.address}:${server.port}` : 'Add a server to get started.'));
      clear(
        hero,
        h('div', { class: 'grow' }, h('div', { class: 'headline', text: app.blocking ? 'Tunnel down' : v.label }), h('div', { class: 'muted', text: detail })),
        h(
          'div',
          { class: 'stack', style: { alignItems: 'flex-end' } },
          h('div', { class: 'row' }, sel, h('button', { class: 'btn', text: 'Fastest', title: 'Test the best servers and connect to the fastest', disabled: locked || ctx.list.servers.length < 2, on: { click: () => void fastest() } })),
          primary,
        ),
      );
    }

    function renderExit() {
      clear(
        exitCard,
        h('div', { class: 'card-title' }, h('h3', { text: 'Exit address' }), h('button', { class: 'btn small', text: 'Check', disabled: !isConnected(), on: { click: () => void checkExit() } })),
        exit
          ? h('div', { class: 'stat' }, h('span', { class: 'v mono', text: exit.ip }), h('span', { class: 'muted small', text: `${exit.country || 'Unknown location'}${exit.colo ? ` · ${exit.colo}` : ''} · ${exit.latencyMs} ms` }))
          : h('p', { class: 'muted small', text: exitError || (isConnected() ? 'See the address websites see.' : 'Connect to check the address websites see.') }),
      );
    }

    async function checkExit() {
      exitError = 'Checking…';
      exit = null;
      renderExit();
      try {
        exit = await call<ExitInfo>('exitInfo', {});
        exitError = '';
      } catch (e) {
        exitError = (e as Error).message;
      }
      renderExit();
    }

    function renderLive() {
      const t = traffic;
      clear(
        liveCard,
        h('div', { class: 'card-title' }, h('h3', { text: 'Speed' }), h('button', { class: 'btn small', text: 'Speed test', disabled: !isConnected(), on: { click: () => void runSpeed() } })),
        t?.counting
          ? h(
              'div',
              { class: 'row', style: { gap: '22px' } },
              h('div', { class: 'stat' }, h('span', { class: 'v', text: rate(t.rate.proxyDown + t.rate.directDown) }), h('span', { class: 'muted small', text: 'Download' })),
              h('div', { class: 'stat' }, h('span', { class: 'v', text: rate(t.rate.proxyUp + t.rate.directUp) }), h('span', { class: 'muted small', text: 'Upload' })),
            )
          : h('p', { class: 'muted small', text: !isConnected() ? 'Live speed appears while connected.' : t && !t.enabled ? 'Traffic counting is off in Settings.' : 'Measuring…' }),
        t?.counting ? h('p', { class: 'muted small', text: `This session: ${bytes(t.session.proxyDown + t.session.directDown)} down, ${bytes(t.session.proxyUp + t.session.directUp)} up` }) : null,
        speed ? h('p', { class: 'small', text: speed }) : null,
      );
    }

    async function runSpeed() {
      speed = 'Measuring download speed…';
      renderLive();
      try {
        const r = await call<{ bytesPerSecond: number }>('speedTest', {});
        speed = `Speed test: ${mbps(r.bytesPerSecond)}`;
      } catch (e) {
        speed = (e as Error).message;
      }
      renderLive();
    }

    function renderQuality() {
      const q = quality;
      clear(
        qualityCard,
        h('div', { class: 'card-title' }, h('h3', { text: 'Route quality' }), q ? h('span', { class: `chip ${q.label}`, text: q.score === null ? 'Unknown' : `${q.score} · ${q.label[0].toUpperCase()}${q.label.slice(1)}` }) : null),
        q ? h('p', { class: 'small', text: q.headline }) : null,
        h('p', { class: 'muted small', text: q ? q.explanation : 'Connect or test a server to rate it.' }),
      );
    }

    function renderProtect() {
      const s = ctx.app?.settings;
      const set = async (patch: Partial<Settings>) => {
        const r = await request('setSettings', patch);
        if (!r.ok) toast(r.error.message, 'error');
      };
      const line = (title: string, text: string, value: boolean, key: keyof Settings) =>
        h('div', { class: 'setting' }, h('div', { class: 'text' }, h('b', { text: title }), h('span', { text })), toggle(value, (v) => set({ [key]: v } as Partial<Settings>), title));
      clear(
        protectCard,
        h('h3', { text: 'Protection' }),
        s
          ? h(
              'div',
              null,
              line('Kill switch', 'If the tunnel drops, sites that use it stay offline instead of opening directly.', s.killSwitch, 'killSwitch'),
              line('Automatic failover', 'Move to a working server when the current one stops responding.', s.autoFailover, 'autoFailover'),
              line('WebRTC protection', 'Stop WebRTC from revealing your real address while connected.', s.webrtcProtection, 'webrtcProtection'),
            )
          : h('p', { class: 'muted small', text: 'Loading…' }),
      );
    }

    function renderLocal() {
      const jb = ctx.app?.status?.jetbrains;
      const mode = jb?.mode === 'tunnel' ? 'through the tunnel' : jb?.mode === 'direct' ? 'direct (not connected)' : 'off';
      clear(
        localCard,
        h('div', { class: 'card-title' }, h('h3', { text: 'Local proxy for other apps' }), h('a', { text: 'Settings', attrs: { href: '#settings' } })),
        jb?.enabled
          ? h(
              'dl',
              { class: 'kv' },
              h('dt', { text: 'HTTP' }),
              h('dd', { class: 'mono', text: jb.httpPort ? `127.0.0.1:${jb.httpPort}` : 'unavailable' }),
              h('dt', { text: 'SOCKS5' }),
              h('dd', { class: 'mono', text: jb.socksPort ? `127.0.0.1:${jb.socksPort}` : 'unavailable' }),
              h('dt', { text: 'Route' }),
              h('dd', { text: mode }),
              h('dt', { text: 'Password' }),
              h('dd', { text: jb.authRequired ? 'required' : 'not required' }),
            )
          : h('p', { class: 'muted small', text: 'Off. Turn it on in Settings to let IDEs and other proxy-aware programs use the tunnel.' }),
        jb?.issue ? h('p', { class: 'small', style: { color: 'var(--warn)' }, text: jb.issue }) : null,
      );
    }

    function update() {
      renderHero();
      renderExit();
      renderLive();
      renderProtect();
      renderLocal();
      const id = activeServerId() ?? ctx.list.selectedServerId;
      if (id !== qualityFor) void loadQuality();
      else renderQuality();
    }

    update();
    void loadTraffic();
    const timer = setInterval(() => void loadTraffic(), 3000);
    return {
      update,
      unmount: () => clearInterval(timer),
    };
  },
};
