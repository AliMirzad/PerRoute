// A small element builder. Text is always set with textContent (helper data, server names and
// rule values are untrusted); nothing is ever assigned as HTML.

export type Child = Node | string | number | null | undefined | false;

export interface Props {
  class?: string;
  text?: string;
  title?: string;
  id?: string;
  hidden?: boolean;
  disabled?: boolean;
  type?: string;
  value?: string;
  placeholder?: string;
  checked?: boolean;
  for?: string;
  role?: string;
  tabindex?: number;
  ariaLabel?: string;
  data?: Record<string, string>;
  style?: Partial<CSSStyleDeclaration>;
  on?: { [K in keyof HTMLElementEventMap]?: (e: HTMLElementEventMap[K]) => void };
  attrs?: Record<string, string>;
}

function append(el: Element, children: Child[]) {
  for (const c of children) {
    if (c === null || c === undefined || c === false) continue;
    el.append(typeof c === 'string' || typeof c === 'number' ? document.createTextNode(String(c)) : c);
  }
}

export function h<K extends keyof HTMLElementTagNameMap>(tag: K, props: Props | null = null, ...children: Child[]): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  if (props) {
    if (props.class) el.className = props.class;
    if (props.text !== undefined) el.textContent = props.text;
    if (props.title) el.title = props.title;
    if (props.id) el.id = props.id;
    if (props.hidden) el.hidden = true;
    if (props.role) el.setAttribute('role', props.role);
    if (props.tabindex !== undefined) el.tabIndex = props.tabindex;
    if (props.ariaLabel) el.setAttribute('aria-label', props.ariaLabel);
    if (props.for) el.setAttribute('for', props.for);
    const anyEl = el as unknown as Record<string, unknown>;
    if (props.disabled !== undefined && 'disabled' in el) anyEl.disabled = props.disabled;
    if (props.type && 'type' in el) anyEl.type = props.type;
    if (props.value !== undefined && 'value' in el) anyEl.value = props.value;
    if (props.placeholder && 'placeholder' in el) anyEl.placeholder = props.placeholder;
    if (props.checked !== undefined && 'checked' in el) anyEl.checked = props.checked;
    if (props.data) for (const [k, v] of Object.entries(props.data)) el.dataset[k] = v;
    if (props.style) Object.assign(el.style, props.style);
    if (props.attrs) for (const [k, v] of Object.entries(props.attrs)) el.setAttribute(k, v);
    if (props.on) for (const [k, fn] of Object.entries(props.on)) el.addEventListener(k, fn as EventListener);
  }
  append(el, children);
  return el;
}

export const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;

export function clear(el: Element, ...children: Child[]) {
  el.replaceChildren();
  append(el, children);
}

/** A <select> with options; `value` is selected if present. */
export function select(options: [string, string][], value: string, onChange: (v: string) => void, props: Props = {}): HTMLSelectElement {
  const s = h('select', props);
  for (const [v, t] of options) s.append(h('option', { value: v, text: t }));
  if (options.some(([v]) => v === value)) s.value = value;
  s.onchange = () => onChange(s.value);
  return s;
}

// ------------------------------------------------------------------ regions

const FORM_TAGS = new Set(['INPUT', 'SELECT', 'TEXTAREA']);

/**
 * A part of the page that is rebuilt from state. It is rebuilt only when its key changes, and
 * never while the user is typing in it or has one of its lists open: then it waits until the
 * field loses focus. Open <details data-key> and scroll positions survive a rebuild.
 */
export interface Region {
  el: HTMLElement;
  /** Rebuild if the key changed (or always, with `force`). */
  render(force?: boolean): void;
}

export function region(el: HTMLElement, build: () => Child[], key?: () => unknown): Region {
  let last: string | null = null;
  let waiting = false;
  const busy = () => {
    const a = document.activeElement;
    return !!a && el.contains(a) && FORM_TAGS.has(a.tagName) && (a as HTMLInputElement).type !== 'checkbox' && (a as HTMLInputElement).type !== 'button';
  };
  const r: Region = {
    el,
    render(force = false) {
      const k = key ? JSON.stringify(key()) : null;
      if (!force && k !== null && k === last) return;
      if (busy()) {
        if (!waiting) {
          waiting = true;
          el.addEventListener(
            'focusout',
            () =>
              setTimeout(() => {
                waiting = false;
                r.render(true);
              }, 0),
            { once: true },
          );
        }
        return;
      }
      last = k;
      const open = new Set([...el.querySelectorAll<HTMLDetailsElement>('details[data-key]')].filter((d) => d.open).map((d) => d.dataset.key));
      const scroll = [...el.querySelectorAll<HTMLElement>('[data-scroll]')].map((s) => [s.dataset.scroll, s.scrollTop, s.scrollLeft] as const);
      clear(el, ...build());
      for (const d of el.querySelectorAll<HTMLDetailsElement>('details[data-key]')) if (open.has(d.dataset.key)) d.open = true;
      for (const [k2, top, left] of scroll) {
        const s = el.querySelector<HTMLElement>(`[data-scroll="${CSS.escape(k2 ?? '')}"]`);
        if (s) {
          s.scrollTop = top;
          s.scrollLeft = left;
        }
      }
    },
  };
  return r;
}

// ------------------------------------------------------------------ controls

/** A button that asks for a second click before a destructive action. */
export function confirmButton(label: string, confirmLabel: string, cls: string, action: () => void | Promise<void>): HTMLButtonElement {
  const b = h('button', { class: cls, text: label, type: 'button' });
  let timer: ReturnType<typeof setTimeout> | null = null;
  b.onclick = async () => {
    if (b.dataset.armed !== '1') {
      b.dataset.armed = '1';
      b.textContent = confirmLabel;
      timer = setTimeout(() => {
        b.dataset.armed = '';
        b.textContent = label;
      }, 4000);
      return;
    }
    if (timer) clearTimeout(timer);
    b.dataset.armed = '';
    b.textContent = label;
    b.disabled = true;
    try {
      await action();
    } finally {
      b.disabled = false;
    }
  };
  return b;
}

/** A switch. While `onChange` runs it is disabled; if it throws or returns false it flips back. */
export function toggle(checked: boolean, onChange: (v: boolean) => unknown, label?: string, disabled = false): HTMLLabelElement {
  const input = h('input', { type: 'checkbox', checked, disabled, attrs: { role: 'switch' } });
  if (label) input.setAttribute('aria-label', label);
  input.onchange = async () => {
    const v = input.checked;
    input.disabled = true;
    try {
      if ((await onChange(v)) === false) input.checked = !v;
    } catch {
      input.checked = !v;
    } finally {
      input.disabled = disabled;
    }
  };
  return h('label', { class: 'switch', title: label }, input, h('span', { class: 'track' }));
}

/** A button that runs an async action, disabled (with a busy label) until it finishes. */
export function actionButton(label: string, cls: string, action: () => Promise<unknown>, busyLabel?: string, props: Props = {}): HTMLButtonElement {
  const b = h('button', { ...props, class: cls, type: 'button' }, label);
  b.onclick = async () => {
    const before = [...b.childNodes];
    b.disabled = true;
    if (busyLabel) b.textContent = busyLabel;
    try {
      await action();
    } catch (e) {
      toast((e as Error).message, 'error');
    } finally {
      if (b.isConnected) {
        b.replaceChildren(...before);
        b.disabled = props.disabled ?? false;
      }
    }
  };
  return b;
}

export interface MenuItem {
  label: string;
  danger?: boolean;
  disabled?: boolean;
  /** A line above this item. */
  separated?: boolean;
  run: () => void | Promise<void>;
}

let openMenu: { el: HTMLElement; close: () => void } | null = null;

/** A "⋯" button with a popup menu. */
export function menuButton(items: () => MenuItem[], props: { label?: string; content?: Node } = {}): HTMLButtonElement {
  const b = h('button', { class: 'btn ghost icon small menu-btn', type: 'button', ariaLabel: props.label ?? 'More actions', title: props.label ?? 'More actions', attrs: { 'aria-haspopup': 'menu' } }, props.content ?? '⋯');
  b.onclick = (e) => {
    e.stopPropagation();
    const wasMine = openMenu && (openMenu.el as HTMLElement & { owner?: HTMLElement }).owner === b;
    openMenu?.close();
    if (wasMine) return;
    const m = h('div', { class: 'menu', role: 'menu' }) as HTMLElement & { owner?: HTMLElement };
    m.owner = b;
    for (const it of items()) {
      if (it.separated) m.append(h('div', { class: 'menu-sep' }));
      m.append(
        h('button', {
          class: `menu-item ${it.danger ? 'danger' : ''}`,
          role: 'menuitem',
          type: 'button',
          text: it.label,
          disabled: it.disabled,
          on: {
            click: () => {
              close();
              void Promise.resolve(it.run()).catch((err: Error) => toast(err.message, 'error'));
            },
          },
        }),
      );
    }
    document.body.append(m);
    const r = b.getBoundingClientRect();
    const mw = m.offsetWidth;
    const mh = m.offsetHeight;
    const left = Math.max(8, Math.min(r.right - mw, window.innerWidth - mw - 8));
    const below = r.bottom + 4 + mh < window.innerHeight;
    m.style.left = `${left + window.scrollX}px`;
    m.style.top = `${(below ? r.bottom + 4 : Math.max(8, r.top - mh - 4)) + window.scrollY}px`;
    const onDoc = (ev: Event) => {
      if (!m.contains(ev.target as Node)) close();
    };
    const onKey = (ev: KeyboardEvent) => {
      if (ev.key === 'Escape') {
        close();
        b.focus();
      }
    };
    function close() {
      m.remove();
      document.removeEventListener('mousedown', onDoc, true);
      document.removeEventListener('keydown', onKey, true);
      window.removeEventListener('blur', close);
      window.removeEventListener('resize', close);
      if (openMenu?.el === m) openMenu = null;
    }
    document.addEventListener('mousedown', onDoc, true);
    document.addEventListener('keydown', onKey, true);
    window.addEventListener('blur', close);
    window.addEventListener('resize', close);
    openMenu = { el: m, close };
    (m.querySelector('button:not(:disabled)') as HTMLButtonElement | null)?.focus();
  };
  return b;
}

// ------------------------------------------------------------------ dialogs

export function dialog(title: string, body: Child | Child[], actions: HTMLElement[], opts: { wide?: boolean } = {}): HTMLDialogElement {
  const close = h('button', { class: 'btn ghost icon small dialog-x', type: 'button', ariaLabel: 'Close', text: '✕' });
  const d = h(
    'dialog',
    { class: opts.wide ? 'wide' : '' },
    h('div', { class: 'dialog-head' }, h('h2', { class: 'grow', text: title }), close),
    h('div', { class: 'dialog-body stack' }, ...(Array.isArray(body) ? body : [body])),
    actions.length ? h('div', { class: 'dialog-actions' }, ...actions) : null,
  );
  close.onclick = () => d.close();
  // A click on the backdrop closes the dialog.
  d.addEventListener('mousedown', (e) => {
    if (e.target === d) {
      const r = d.getBoundingClientRect();
      if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) d.close();
    }
  });
  d.addEventListener('close', () => d.remove());
  document.body.append(d);
  d.showModal();
  return d;
}

/** A styled confirmation. Resolves to true if the user confirms. */
export function confirmDialog(title: string, text: string, confirmLabel = 'Continue', danger = false): Promise<boolean> {
  return new Promise((resolve) => {
    let result = false;
    const ok = h('button', { class: `btn ${danger ? 'danger solid' : 'primary'}`, type: 'button', text: confirmLabel });
    const cancel = h('button', { class: 'btn', type: 'button', text: 'Cancel' });
    const d = dialog(title, h('p', { text }), [cancel, ok]);
    ok.onclick = () => {
      result = true;
      d.close();
    };
    cancel.onclick = () => d.close();
    d.addEventListener('close', () => resolve(result));
    ok.focus();
  });
}

/** Asks for one line of text. `onSave` may throw to keep the dialog open with its message. */
export function promptDialog(title: string, label: string, value: string, onSave: (v: string) => Promise<void>, opts: { suggestions?: string[]; placeholder?: string; allowEmpty?: boolean } = {}) {
  const input = h('input', { type: 'text', value, placeholder: opts.placeholder, style: { width: '100%' } });
  const err = h('p', { class: 'small error-text', hidden: true });
  const save = h('button', { class: 'btn primary', type: 'button', text: 'Save' });
  const chips = opts.suggestions?.length
    ? h(
        'div',
        { class: 'row wrap' },
        ...opts.suggestions.map((s) =>
          h('button', {
            class: 'chip-btn',
            type: 'button',
            text: s,
            on: {
              click: () => {
                input.value = s;
                void go();
              },
            },
          }),
        ),
      )
    : null;
  const d = dialog(title, [h('label', { class: 'field' }, h('span', { text: label }), input), chips, err], [h('button', { class: 'btn', type: 'button', text: 'Cancel', on: { click: () => d.close() } }), save]);
  async function go() {
    const v = input.value.trim();
    if (!v && !opts.allowEmpty) {
      input.focus();
      return;
    }
    save.disabled = true;
    try {
      await onSave(v);
      d.close();
    } catch (e) {
      err.textContent = (e as Error).message;
      err.hidden = false;
      save.disabled = false;
    }
  }
  save.onclick = () => void go();
  input.onkeydown = (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      void go();
    }
  };
  input.focus();
  input.select();
}

// ------------------------------------------------------------------ toast

let toastTimer: ReturnType<typeof setTimeout> | null = null;
export function toast(message: string, tone: 'ok' | 'error' = 'ok') {
  let t = document.getElementById('toast');
  if (!t) {
    t = h('div', { id: 'toast', role: 'status', attrs: { 'aria-live': 'polite' } });
    document.body.append(t);
  }
  t.textContent = message;
  t.dataset.tone = tone;
  t.classList.add('show');
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t?.classList.remove('show'), tone === 'error' ? 6000 : 3500);
}

/** Copies text and says so. */
export async function copyText(text: string, what: string) {
  try {
    await navigator.clipboard.writeText(text);
    toast(`${what} copied.`);
  } catch {
    toast('Could not copy to the clipboard.', 'error');
  }
}
