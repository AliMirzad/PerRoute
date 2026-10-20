// The full PerRoute page: everything the desktop app offers that applies to a browser.
import type { NativeEvent } from '../../../protocol/types';
import { dismissNotices, retryNative, subscribe } from '../shared/client';
import { $, clear, h, region } from '../shared/dom';
import { icon } from '../shared/icons';
import { applyTheme } from '../shared/theme';
import { deriveView } from '../shared/view';
import { ctx, dispatchEvent, isReady, refreshList, refreshRules } from './context';
import type { Mounted, Section } from './section';
import { connectSection } from './sections/connect';
import { doctorSection } from './sections/doctor';
import { profilesSection } from './sections/profiles';
import { rulesSection } from './sections/rules';
import { serversSection } from './sections/servers';
import { settingsSection } from './sections/settings';
import { trafficSection } from './sections/traffic';

const SECTIONS: Section[] = [connectSection, serversSection, rulesSection, profilesSection, trafficSection, doctorSection, settingsSection];

let current: Section = SECTIONS[0];
let mounted: Mounted | null = null;

function sectionFromHash(): Section {
  const id = location.hash.replace(/^#/, '');
  return SECTIONS.find((s) => s.id === id) ?? SECTIONS[0];
}

const rail = region($('rail'), () => [
  h('div', { class: 'brand' }, h('img', { attrs: { src: 'icons/icon-48.png', alt: '' } }), 'PerRoute'),
  ...SECTIONS.map((s) =>
    h(
      'a',
      { class: 'rail-item', attrs: { href: `#${s.id}`, ...(s === current ? { 'aria-current': 'page' } : {}) } },
      icon(s.icon, 18),
      h('span', { text: s.title }),
    ),
  ),
  h('div', { class: 'foot', text: `Extension ${ctx.app?.extensionVersion ?? ''}` }),
], () => [current.id, ctx.app?.extensionVersion]);

const bar = region($('bar-status'), () => {
  if (!ctx.app) return [];
  const v = deriveView(ctx.app);
  return [h('span', { class: `dot ${v.tone}` }), h('span', { class: 'small', text: v.label })];
}, () => (ctx.app ? deriveView(ctx.app) : null));

const notices = region($('notices'), () => {
  const app = ctx.app;
  if (!app) return [];
  const items: HTMLElement[] = [];
  if (!isReady()) {
    const v = deriveView(app);
    items.push(
      h(
        'div',
        { class: 'banner error' },
        h('div', { class: 'grow' }, h('b', { text: v.label }), h('div', { class: 'small', text: [v.detail, v.hint].filter(Boolean).join(' ') })),
        v.action === 'retry' ? h('button', { class: 'btn small', text: 'Retry', on: { click: () => void retryNative() } }) : null,
      ),
    );
  }
  if (app.proxyError) items.push(h('div', { class: 'banner error' }, h('div', { class: 'grow small', text: app.proxyError })));
  if (app.notices.length) {
    items.push(
      h(
        'div',
        { class: `banner ${app.notices[0].kind === 'routeDown' ? 'error' : 'info'}` },
        h('div', { class: 'grow small', text: app.notices[0].message }),
        h('button', { class: 'link small', text: 'Dismiss', on: { click: () => void dismissNotices() } }),
      ),
    );
  }
  return items;
}, () => [ctx.app?.runtime, ctx.app?.proxyError, ctx.app?.notices[0]]);

function mount() {
  mounted?.unmount?.();
  current = sectionFromHash();
  document.title = `${current.title} · PerRoute`;
  rail.render();
  $('section-title').textContent = current.title;
  $('section-sub').textContent = current.subtitle;
  const el = $('section');
  clear(el);
  mounted = current.mount(el);
  window.scrollTo(0, 0);
}

ctx.rerender = () => {
  bar.render();
  notices.render();
  mounted?.update();
};

async function main() {
  await applyTheme();
  window.addEventListener('hashchange', mount);
  mount();
  let wasReady = false;
  subscribe(
    (s) => {
      ctx.app = s;
      if (s.rules) ctx.rules = s.rules;
      const ready = s.runtime.kind === 'ready';
      if (ready && !wasReady) {
        void refreshList();
        void refreshRules();
      }
      wasReady = ready;
      rail.render();
      ctx.rerender();
    },
    (e: NativeEvent) => {
      if (e.event === 'changed') {
        void refreshList();
        void refreshRules();
      }
      dispatchEvent(e);
      ctx.rerender();
    },
  );
}

void main();
