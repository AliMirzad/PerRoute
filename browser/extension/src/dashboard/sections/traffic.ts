import type { Traffic, Totals } from '../../../../protocol/types';
import { request } from '../../shared/client';
import { clear, confirmButton, h, toast, toggle } from '../../shared/dom';
import { bytes, rate } from '../../shared/format';
import { ctx, isReady } from '../context';
import type { Section } from '../section';

const sumDown = (t: Totals) => t.proxyDown + t.directDown;
const sumUp = (t: Totals) => t.proxyUp + t.directUp;

export const trafficSection: Section = {
  id: 'traffic',
  title: 'Traffic',
  subtitle: 'Live speed and totals for this browser. Only amounts are counted, never which sites.',
  icon: 'traffic',
  mount(el) {
    let t: Traffic | null = null;
    const points: { down: number; up: number }[] = [];
    const live = h('section', { class: 'card stack' });
    const canvas = h('canvas', { class: 'chart' });
    const totals = h('div', { class: 'grid3' });
    const days = h('section', { class: 'card stack' });
    el.append(live, totals, days);

    function draw() {
      const dpr = window.devicePixelRatio || 1;
      const w = canvas.clientWidth;
      const hgt = canvas.clientHeight;
      if (!w || !hgt) return;
      canvas.width = w * dpr;
      canvas.height = hgt * dpr;
      const g = canvas.getContext('2d');
      if (!g) return;
      g.scale(dpr, dpr);
      g.clearRect(0, 0, w, hgt);
      const max = Math.max(1, ...points.map((p) => Math.max(p.down, p.up)));
      const css = getComputedStyle(document.documentElement);
      const line = (key: 'down' | 'up', color: string) => {
        g.beginPath();
        points.forEach((p, i) => {
          const x = (i / Math.max(1, 59)) * w;
          const y = hgt - 4 - (p[key] / max) * (hgt - 12);
          if (i === 0) g.moveTo(x, y);
          else g.lineTo(x, y);
        });
        g.strokeStyle = color;
        g.lineWidth = 2;
        g.lineJoin = 'round';
        g.stroke();
      };
      g.strokeStyle = css.getPropertyValue('--line');
      g.lineWidth = 1;
      g.beginPath();
      g.moveTo(0, hgt - 0.5);
      g.lineTo(w, hgt - 0.5);
      g.stroke();
      line('up', css.getPropertyValue('--direct').trim() || '#888');
      line('down', css.getPropertyValue('--primary-strong').trim() || '#8db600');
    }

    function render() {
      const s = ctx.app?.settings;
      clear(
        live,
        h(
          'div',
          { class: 'card-title' },
          h('h2', { text: 'Live' }),
          t?.counting ? h('span', { class: 'small', text: `↓ ${rate(sumDown(t.rate))}   ↑ ${rate(sumUp(t.rate))}` }) : null,
          h(
            'div',
            { class: 'row small' },
            h('span', { class: 'muted', text: 'Count traffic' }),
            toggle(s?.trafficStats ?? true, async (v) => {
              const r = await request('setSettings', { trafficStats: v });
              if (!r.ok) toast(r.error.message, 'error');
              else if ((r.result as { reconnectRequired?: boolean }).reconnectRequired) toast('Reconnect to apply.');
            }),
          ),
        ),
        t?.counting ? canvas : h('p', { class: 'muted small', text: s?.trafficStats === false ? 'Traffic counting is off.' : 'The live graph appears while connected.' }),
      );
      const card = (title: string, x: Totals | undefined) =>
        h(
          'section',
          { class: 'card stack' },
          h('h3', { text: title }),
          h('div', { class: 'stat' }, h('span', { class: 'v', text: x ? bytes(sumDown(x) + sumUp(x)) : '—' }), h('span', { class: 'muted small', text: x ? `↓ ${bytes(sumDown(x))} · ↑ ${bytes(sumUp(x))}` : '' })),
          x ? h('span', { class: 'muted small', text: `Tunnel ${bytes(x.proxyDown + x.proxyUp)} · Direct ${bytes(x.directDown + x.directUp)}` }) : null,
        );
      clear(totals, card('This session', t?.session), card('Today', t?.today), card('This month', t?.month));
      const recent = (t?.days ?? []).slice(0, 30).reverse();
      const max = Math.max(1, ...recent.map((d) => sumDown(d) + sumUp(d)));
      clear(
        days,
        h(
          'div',
          { class: 'card-title' },
          h('h2', { text: 'Last 30 days' }),
          confirmButton('Clear history', 'Clear all history?', 'btn small danger', async () => {
            await request('clearTraffic', {});
            await load();
          }),
        ),
        recent.length
          ? h(
              'div',
              { class: 'bars' },
              ...recent.map((d) => {
                const total = sumDown(d) + sumUp(d);
                const direct = d.directDown + d.directUp;
                return h(
                  'div',
                  { title: `${d.day}: ${bytes(total)} (tunnel ${bytes(total - direct)}, direct ${bytes(direct)})`, style: { height: `${Math.max(2, (total / max) * 100)}%` } },
                  h('i', { style: { height: `${total ? (direct / total) * 100 : 0}%` } }),
                );
              }),
            )
          : h('div', { class: 'empty', text: 'No traffic recorded yet.' }),
        recent.length ? h('p', { class: 'muted small', text: 'Lime: through the tunnel. Grey: direct.' }) : null,
      );
      if (t?.counting) requestAnimationFrame(draw);
    }

    async function load() {
      if (!isReady()) return render();
      const r = await request('getTraffic', {});
      if (r.ok) {
        t = r.result as Traffic;
        if (t.counting) {
          points.push({ down: sumDown(t.rate), up: sumUp(t.rate) });
          if (points.length > 60) points.shift();
        }
      }
      render();
    }

    void load();
    const timer = setInterval(() => void load(), 3000);
    return { update: () => undefined, unmount: () => clearInterval(timer) };
  },
};
