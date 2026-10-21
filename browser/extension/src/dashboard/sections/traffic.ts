import type { Totals, Traffic } from '../../../../protocol/types';
import { request } from '../../shared/client';
import { confirmButton, h, region, toggle } from '../../shared/dom';
import { bytes, rate } from '../../shared/format';
import { ctx, isReady } from '../context';
import type { Section } from '../section';
import { saveSettings } from './connect';

const sumDown = (t: Totals) => t.proxyDown + t.directDown;
const sumUp = (t: Totals) => t.proxyUp + t.directUp;
const POINTS = 60;

/** The last `n` calendar days, oldest first, with zero for days without traffic. */
function lastDays(days: (Totals & { day: string })[], n: number): (Totals & { day: string })[] {
  const byDay = new Map(days.map((d) => [d.day, d]));
  const out: (Totals & { day: string })[] = [];
  const today = new Date();
  for (let i = n - 1; i >= 0; i--) {
    const d = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate() - i));
    const key = d.toISOString().slice(0, 10);
    out.push(byDay.get(key) ?? { day: key, proxyUp: 0, proxyDown: 0, directUp: 0, directDown: 0 });
  }
  return out;
}

export const trafficSection: Section = {
  id: 'traffic',
  title: 'Traffic',
  subtitle: 'Live speed and totals for this browser. Only amounts are counted, never which sites.',
  icon: 'traffic',
  mount(el) {
    let t: Traffic | null = null;
    const points: { down: number; up: number }[] = [];
    const canvas = h('canvas', { class: 'chart', ariaLabel: 'Live download and upload speed', role: 'img' });

    function draw() {
      const dpr = window.devicePixelRatio || 1;
      const w = canvas.clientWidth;
      const hgt = canvas.clientHeight;
      if (!w || !hgt) return;
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(hgt * dpr);
      const g = canvas.getContext('2d');
      if (!g) return;
      g.setTransform(dpr, 0, 0, dpr, 0, 0);
      g.clearRect(0, 0, w, hgt);
      const max = Math.max(1, ...points.map((p) => Math.max(p.down, p.up)));
      const css = getComputedStyle(document.documentElement);
      const x = (i: number) => w - ((points.length - 1 - i) / (POINTS - 1)) * w;
      const y = (v: number) => hgt - 6 - (v / max) * (hgt - 18);
      const line = (key: 'down' | 'up', color: string, fill: boolean) => {
        if (!points.length) return;
        g.beginPath();
        points.forEach((p, i) => (i === 0 ? g.moveTo(x(i), y(p[key])) : g.lineTo(x(i), y(p[key]))));
        g.strokeStyle = color;
        g.lineWidth = 2;
        g.lineJoin = 'round';
        g.stroke();
        if (fill) {
          g.lineTo(x(points.length - 1), hgt);
          g.lineTo(x(0), hgt);
          g.closePath();
          g.globalAlpha = 0.12;
          g.fillStyle = color;
          g.fill();
          g.globalAlpha = 1;
        }
      };
      g.strokeStyle = css.getPropertyValue('--line').trim() || '#ddd';
      g.lineWidth = 1;
      g.beginPath();
      g.moveTo(0, hgt - 0.5);
      g.lineTo(w, hgt - 0.5);
      g.stroke();
      g.fillStyle = css.getPropertyValue('--muted').trim() || '#888';
      g.font = '11px Inter, system-ui, sans-serif';
      g.fillText(rate(max), 4, 12);
      line('up', css.getPropertyValue('--direct').trim() || '#888', false);
      line('down', css.getPropertyValue('--chart').trim() || '#8db600', true);
    }

    const live = region(h('section', { class: 'card stack' }), () => {
      const s = ctx.app?.settings;
      return [
        h(
          'div',
          { class: 'card-title' },
          h('h2', { text: 'Live' }),
          t?.counting ? h('span', { class: 'legend small' }, h('i', { class: 'down' }), `Download ${rate(sumDown(t.rate))}`, h('i', { class: 'up' }), `Upload ${rate(sumUp(t.rate))}`) : null,
          h('label', { class: 'row small' }, h('span', { class: 'muted', text: 'Count traffic' }), toggle(s?.trafficStats ?? true, (v) => saveSettings({ trafficStats: v }), 'Count traffic', !s)),
        ),
        t?.counting ? canvas : h('p', { class: 'muted small', text: s?.trafficStats === false ? 'Traffic counting is off.' : !isReady() ? 'The runtime is not available.' : 'The live graph appears while connected.' }),
      ];
    }, () => [t?.counting, t?.rate, ctx.app?.settings?.trafficStats, isReady()]);

    const card = (title: string, x: Totals | undefined) =>
      h(
        'section',
        { class: 'card stack' },
        h('h3', { text: title }),
        h('div', { class: 'stat' }, h('span', { class: 'v', text: x ? bytes(sumDown(x) + sumUp(x)) : '—' }), h('span', { class: 'muted small', text: x ? `↓ ${bytes(sumDown(x))} · ↑ ${bytes(sumUp(x))}` : '' })),
        x ? h('span', { class: 'muted small', text: `Tunnel ${bytes(x.proxyDown + x.proxyUp)} · Direct ${bytes(x.directDown + x.directUp)}` }) : null,
      );
    const totals = region(h('div', { class: 'grid3' }), () => [card('This session', t?.session), card('Today', t?.today), card('This month', t?.month)], () => [t?.session, t?.today, t?.month]);

    const days = region(h('section', { class: 'card stack' }), () => {
      const recent = lastDays(t?.days ?? [], 30);
      const any = recent.some((d) => sumDown(d) + sumUp(d) > 0);
      const max = Math.max(1, ...recent.map((d) => sumDown(d) + sumUp(d)));
      return [
        h(
          'div',
          { class: 'card-title' },
          h('h2', { text: 'Last 30 days' }),
          confirmButton('Clear history', 'Clear all history?', 'btn small danger', async () => {
            await request('clearTraffic', {});
            await load();
          }),
        ),
        any
          ? h(
              'div',
              { class: 'bars', role: 'img', ariaLabel: 'Traffic per day for the last 30 days' },
              ...recent.map((d) => {
                const total = sumDown(d) + sumUp(d);
                const direct = d.directDown + d.directUp;
                return h(
                  'div',
                  { class: 'day-bar', title: `${d.day}: ${bytes(total)} (tunnel ${bytes(total - direct)}, direct ${bytes(direct)})` },
                  h('div', { class: 'stack-bar', style: { height: `${total ? Math.max(3, (total / max) * 100) : 0}%` } }, h('i', { class: 'direct', style: { height: `${total ? (direct / total) * 100 : 0}%` } })),
                );
              }),
            )
          : h('div', { class: 'empty', text: 'No traffic recorded yet.' }),
        any ? h('div', { class: 'legend small muted' }, h('i', { class: 'down' }), 'Through the tunnel', h('i', { class: 'up' }), 'Direct', h('span', { class: 'spacer' }), h('span', { text: `${recent[0].day.slice(5)} – ${recent[recent.length - 1].day.slice(5)}` })) : null,
      ];
    }, () => t?.days ?? null);

    el.append(live.el, totals.el, days.el);

    async function load() {
      if (isReady()) {
        const r = await request('getTraffic', {});
        if (r.ok) {
          t = r.result as Traffic;
          if (t.counting) {
            points.push({ down: sumDown(t.rate), up: sumUp(t.rate) });
            if (points.length > POINTS) points.shift();
          } else points.length = 0;
        }
      }
      live.render();
      totals.render();
      days.render();
      if (t?.counting) requestAnimationFrame(draw);
    }

    const onResize = () => t?.counting && draw();
    window.addEventListener('resize', onResize);
    const theme = matchMedia('(prefers-color-scheme: dark)');
    theme.addEventListener('change', onResize);
    void load();
    const timer = setInterval(() => void load(), 2000);
    return {
      update: () => live.render(),
      unmount: () => {
        clearInterval(timer);
        window.removeEventListener('resize', onResize);
        theme.removeEventListener('change', onResize);
      },
    };
  },
};
