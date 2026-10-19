// The full PerRoute page: everything the desktop app offers, for this browser.
import type { NativeEvent } from '../../../protocol/types';
import { dismissNotices, retryNative, subscribe } from '../shared/client';
import { $, clear, h } from '../shared/dom';
import { icon } from '../shared/icons';
import { applyTheme } from '../shared/theme';
import { deriveView } from '../shared/view';
import { ctx, dispatchEvent, isReady, refreshList, refreshRules } from './context';
import type { Section } from './section';
import { connectSection } from './sections/connect';
import { doctorSection } from './sections/doctor';
import { profilesSection } from './sections/profiles';
import { rulesSection } from './sections/rules';
import { serversSection } from './sections/servers';
import { settingsSection } from './sections/settings';
import { trafficSection } from './sections/traffic';

const SECTIONS: Section[] = [connectSection, serversSection, rulesSection, profilesSection, trafficSection, doctorSection, settingsSection];

let current: Section = SECTIONS[0];
let mounted: { update(): void; unmount?(): void } | null = null;

function sectionFromHash(): Section {
  const id = location.hash.replace(/^#/, '');
  return SECTIONS.find((s) => s.id === id) ?? SECTIONS[0];
}

function renderRail() {
  const rail = $('rail');
  clear(
    rail,
    h('div', { class: 'brand' }, h('img', { attrs: { src: 'icons/icon-48.png', alt: '' } }), 'PerRoute'),
    ...SECTIONS.map((s) =>
      h(
        'button',
        { attrs: s === current ? { 'aria-current': 'page' } : {}, on: { click: () => (location.hash = s.id) } },
        icon(s.icon, 18),
        s.title,
      ),
    ),
    h('div', { class: 'foot', text: `Extension ${ctx.app?.extensionVersion ?? ''}` }),
  );
}

function renderBar() {
  $('section-title').textContent = current.title;
  $('section-sub').textContent = current.subtitle;
  const bar = $('bar-status');
  if (!ctx.app) return clear(bar);
  const v = deriveView(ctx.app);
  const blocking = ctx.app.blocking;
  clear(bar, h('span', { class: `dot ${blocking ? 'error' : v.tone}` }), h('span', { class: 'small', text: blocking ? 'Kill switch active' : v.label }));
}

function renderNotices() {
  const el = $('notices');
  const app = ctx.app;
  if (!app) return clear(el);
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
  clear(el, ...items);
}

function mount() {
  mounted?.unmount?.();
  current = sectionFromHash();
  renderRail();
  renderBar();
  const el = $('section');
  clear(el);
  mounted = current.mount(el);
  window.scrollTo(0, 0);
}

ctx.rerender = () => {
  renderBar();
  renderNotices();
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
      renderRail();
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
