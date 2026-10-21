import type { ExitInfo, QualityScore, Settings, Traffic } from '../../../../protocol/types';
import { call, endTrial, request, retryNative, tryServer } from '../../shared/client';
import { actionButton, h, region, select, toast, toggle } from '../../shared/dom';
import { bytes, DURATIONS, durationLabel, mbps, rate, until } from '../../shared/format';
import { icon } from '../../shared/icons';
import { connectedFor, currentServerId, deriveView, primaryLabel, protocolLine } from '../../shared/view';
import { activeServerId, ctx, isConnected, isReady, serverName, sortedServers } from '../context';
import { qualityDetails } from '../quality';
import type { Section } from '../section';

export async function saveSettings(patch: Partial<Settings>): Promise<boolean> {
  const r = await request('setSettings', patch);
  if (!r.ok) {
    toast(r.error.message, 'error');
    return false;
  }
  if ((r.result as { reconnectRequired?: boolean }).reconnectRequired) toast('Saved. Reconnect to apply.');
  return true;
}

export function settingLine(title: string, text: string, control: HTMLElement): HTMLElement {
  return h('div', { class: 'setting' }, h('div', { class: 'text' }, h('b', { text: title }), h('span', { text })), control);
}

export const connectSection: Section = {
  id: 'connect',
  title: 'Connection',
  subtitle: 'Connect, choose a server and see how the route is doing.',
  icon: 'power',
  mount(el) {
    let busy = false;
    let exit: ExitInfo | null = null;
    let exitState = '';
    let speed: string | null = null;
    let quality: QualityScore | null = null;
    let qualityFor: string | null = null;
    let traffic: Traffic | null = null;
    let trialServer = '';
    let trialMinutes = 30;

    async function connectTo(id: string) {
      busy = true;
      hero.render(true);
      const r = await request('connect', { serverId: id });
      busy = false;
      if (!r.ok) toast(r.error.message, 'error');
      hero.render(true);
    }

    async function fastest() {
      busy = true;
      hero.render(true);
      toast('Testing the best servers…');
      try {
        const r = await call<{ serverId: string; ms: number }>('connectFastest', {});
        toast(`Connecting to ${serverName(r.serverId)} (${r.ms} ms).`);
      } catch (e) {
        toast((e as Error).message, 'error');
      }
      busy = false;
      hero.render(true);
    }

    const hero = region(h('section', { class: 'card hero' }), () => {
      const app = ctx.app;
      if (!app) return [];
      const v = deriveView(app);
      const current = currentServerId(app, ctx.list.selectedServerId);
      const server = ctx.list.servers.find((s) => s.id === current);
      const st = app.status?.state;
      const locked = busy || st === 'connecting' || st === 'disconnecting' || !isReady();
      hero.el.dataset.tone = v.tone;
      const primary = h('button', { class: `btn big ${v.action === 'connect' ? 'primary' : ''}`, text: primaryLabel(v.action) });
      primary.disabled = busy || v.action === 'none' || (v.action === 'connect' && !current);
      primary.onclick = async () => {
        if (v.action === 'retry') return void retryNative();
        if (v.action === 'connect') {
          if (app.settings?.fastestOnConnect && ctx.list.servers.length > 1) return void fastest();
          if (current) return void connectTo(current);
          return;
        }
        busy = true;
        hero.render(true);
        const r = await request('disconnect', {});
        busy = false;
        if (!r.ok) toast(r.error.message, 'error');
        hero.render(true);
      };
      const detail = v.detail ?? (server ? `${protocolLine(server)} · ${server.address}:${server.port}` : ctx.list.servers.length ? 'Choose a server.' : 'Add a server to get started.');
      const since = v.tone === 'ok' ? connectedFor(app.status?.since) : null;
      const latency = server ? ctx.list.scores?.[server.id]?.latencyMs : null;
      const trial = app.trial;
      return [
        h(
          'div',
          { class: 'grow stack', style: { gap: '4px' } },
          h('div', { class: 'headline', text: v.label }),
          h('div', { class: 'muted', text: detail }),
          v.hint ? h('div', { class: 'small', text: v.hint }) : null,
          since || latency ? h('div', { class: 'row small muted wrap' }, since ? h('span', null, icon('clock', 13), ` Connected for ${since}`) : null, latency ? h('span', { text: `· ${latency} ms latency` }) : null) : null,
          trial
            ? h(
                'div',
                { class: 'trial' },
                icon('clock', 14),
                h('span', { class: 'grow', text: `Trying ${serverName(trial.serverId)} for ${until(trial.until / 1000)} more, then back to ${trial.previousId ? serverName(trial.previousId) : 'the previous server'}.` }),
                h('button', { class: 'btn small', text: 'End now', on: { click: () => void endTrial() } }),
              )
            : null,
        ),
        h(
          'div',
          { class: 'hero-controls' },
          h(
            'div',
            { class: 'row' },
            ctx.list.servers.length
              ? select(
                  sortedServers().map((s) => [s.id, `${ctx.list.favorites?.includes(s.id) ? '★ ' : ''}${s.name}`]),
                  current ?? '',
                  async (id) => {
                    await request('selectServer', { id });
                    ctx.list.selectedServerId = id;
                    if (isConnected()) await connectTo(id);
                    else hero.render(true);
                  },
                  { ariaLabel: 'Server', disabled: locked, class: 'grow' },
                )
              : h('a', { class: 'btn', attrs: { href: '#servers' } }, icon('plus', 14), 'Add servers'),
            h('button', { class: 'btn', title: 'Test the best servers and connect to the fastest', disabled: locked || ctx.list.servers.length < 2, on: { click: () => void fastest() } }, icon('bolt', 14), 'Fastest'),
          ),
          primary,
        ),
      ];
    }, () => [ctx.app?.runtime.kind, ctx.app?.status, ctx.app?.blocking, ctx.app?.proxyError, ctx.app?.trial, ctx.app?.settings?.fastestOnConnect, ctx.list.servers, ctx.list.favorites, ctx.list.scores, ctx.list.selectedServerId, busy, Math.floor(Date.now() / 30000)]);

    const exitCard = region(h('section', { class: 'card stack' }), () => [
      h(
        'div',
        { class: 'card-title' },
        h('h3', { text: 'Exit address' }),
        actionButton('Check', 'btn small', async () => {
          exitState = 'Checking…';
          exit = null;
          exitCard.render(true);
          try {
            exit = await call<ExitInfo>('exitInfo', {});
            exitState = '';
          } catch (e) {
            exitState = (e as Error).message;
          }
          exitCard.render(true);
        }, undefined, { disabled: !isConnected() }),
      ),
      exit
        ? h('div', { class: 'stat' }, h('span', { class: 'v mono-num', text: exit.ip }), h('span', { class: 'muted small', text: `${exit.country || 'Unknown location'}${exit.colo ? ` · ${exit.colo}` : ''} · ${exit.latencyMs} ms` }))
        : h('p', { class: 'muted small', text: exitState || (isConnected() ? 'See the address websites see.' : 'Connect to check the address websites see.') }),
    ], () => [isConnected(), exit, exitState]);

    const liveCard = region(h('section', { class: 'card stack' }), () => {
      const t = traffic;
      return [
        h(
          'div',
          { class: 'card-title' },
          h('h3', { text: 'Speed' }),
          actionButton('Speed test', 'btn small', async () => {
            speed = 'Measuring download speed…';
            liveCard.render(true);
            try {
              const r = await call<{ bytesPerSecond: number }>('speedTest', {});
              speed = `Speed test: ${mbps(r.bytesPerSecond)}`;
            } catch (e) {
              speed = (e as Error).message;
            }
            liveCard.render(true);
          }, 'Measuring…', { disabled: !isConnected() }),
        ),
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
      ];
    }, () => [isConnected(), traffic?.counting, traffic?.enabled, traffic?.rate, traffic?.session, speed]);

    const qualityCard = region(h('section', { class: 'card stack' }), () => [
      h('div', { class: 'card-title' }, h('h3', { text: 'Route quality' }), qualityFor ? h('span', { class: 'muted small ellipsis', text: serverName(qualityFor) }) : null),
      quality ? qualityDetails(quality) : h('p', { class: 'muted small', text: 'Connect or test a server to rate it.' }),
    ], () => [quality, qualityFor, ctx.list.servers.length]);

    async function loadQuality() {
      const id = activeServerId() ?? ctx.list.selectedServerId;
      qualityFor = id;
      if (!id || !isReady()) {
        quality = null;
      } else {
        const r = await request('getQuality', { id });
        if (id !== qualityFor) return;
        quality = r.ok ? (r.result as QualityScore) : null;
      }
      qualityCard.render();
    }

    const trialCard = region(h('section', { class: 'card stack' }), () => {
      const servers = sortedServers().filter((s) => s.id !== activeServerId());
      if (!trialServer || !servers.some((s) => s.id === trialServer)) trialServer = servers[0]?.id ?? '';
      return [
        h('h3', { text: 'Try another server for a while' }),
        h('p', { class: 'muted small', text: 'The browser uses it for the time you choose, then goes back to the server it uses now. Your selection and rules do not change.' }),
        servers.length
          ? h(
              'div',
              { class: 'row wrap' },
              select(servers.map((s) => [s.id, s.name]), trialServer, (v) => (trialServer = v), { ariaLabel: 'Server to try', class: 'grow' }),
              select(DURATIONS.map((m) => [String(m), durationLabel(m)]), String(trialMinutes), (v) => (trialMinutes = Number(v)), { ariaLabel: 'For how long' }),
              actionButton('Try', 'btn primary', async () => {
                const r = await tryServer(trialServer, trialMinutes);
                if (!r.ok) throw new Error(r.error.message);
                toast(`Trying ${serverName(trialServer)} for ${durationLabel(trialMinutes)}.`);
              }, 'Connecting…', { disabled: !isReady() || !trialServer }),
            )
          : h('p', { class: 'muted small', text: 'Add another server to try it.' }),
      ];
    }, () => [isReady(), activeServerId(), ctx.list.servers.map((s) => [s.id, s.name]), ctx.list.favorites]);

    const protectCard = region(h('section', { class: 'card' }), () => {
      const s = ctx.app?.settings;
      const line = (title: string, text: string, key: 'killSwitch' | 'autoFailover' | 'webrtcProtection') => settingLine(title, text, toggle(!!s?.[key], (v) => saveSettings({ [key]: v }), title, !s));
      return [
        h('h3', { text: 'Protection' }),
        line('Kill switch', 'If the tunnel drops, sites that use it stay offline instead of opening directly.', 'killSwitch'),
        line('Automatic failover', 'Move to a working server when the current one stops responding.', 'autoFailover'),
        line('WebRTC protection', 'Stop WebRTC from revealing your real address while connected.', 'webrtcProtection'),
      ];
    }, () => [ctx.app?.settings?.killSwitch, ctx.app?.settings?.autoFailover, ctx.app?.settings?.webrtcProtection, !!ctx.app?.settings]);

    const localCard = region(h('section', { class: 'card stack' }), () => {
      const jb = ctx.app?.status?.jetbrains;
      const mode = jb?.mode === 'tunnel' ? 'Through the tunnel' : jb?.mode === 'direct' ? 'Direct (not connected)' : 'Off';
      return [
        h('div', { class: 'card-title' }, h('h3', { text: 'Local proxy for other apps' }), h('a', { class: 'small', text: 'Settings', attrs: { href: '#settings' } })),
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
              h('dd', { text: jb.authRequired ? 'Required' : 'Not required' }),
            )
          : h('p', { class: 'muted small', text: 'Off. Turn it on in Settings to let IDEs and other proxy-aware programs use the tunnel.' }),
        jb?.issue ? h('p', { class: 'small warn-text', text: jb.issue }) : null,
      ];
    }, () => ctx.app?.status?.jetbrains ?? null);

    el.append(hero.el, h('div', { class: 'grid3' }, exitCard.el, liveCard.el, qualityCard.el), h('div', { class: 'grid2' }, trialCard.el, protectCard.el), localCard.el);

    async function loadTraffic() {
      if (!isReady() || !isConnected()) {
        traffic = null;
      } else {
        const r = await request('getTraffic', {});
        traffic = r.ok ? (r.result as Traffic) : null;
      }
      liveCard.render();
    }

    function update() {
      if (isConnected() && !traffic) void loadTraffic();
      hero.render();
      exitCard.render();
      liveCard.render();
      trialCard.render();
      protectCard.render();
      localCard.render();
      const id = activeServerId() ?? ctx.list.selectedServerId;
      if (id !== qualityFor) void loadQuality();
      else qualityCard.render();
    }

    update();
    void loadTraffic();
    const timer = setInterval(() => {
      void loadTraffic();
      hero.render();
    }, 3000);
    const qTimer = setInterval(() => void loadQuality(), 60_000);
    return {
      update,
      unmount: () => {
        clearInterval(timer);
        clearInterval(qTimer);
      },
    };
  },
};
