// A small element builder. Text is always set with textContent (helper data, server names and
// rule values are untrusted); nothing is ever assigned as HTML.

type Child = Node | string | number | null | undefined | false;

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
  for (const c of children) {
    if (c === null || c === undefined || c === false) continue;
    el.append(typeof c === 'string' || typeof c === 'number' ? document.createTextNode(String(c)) : c);
  }
  return el;
}

export const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;

export function clear(el: Element, ...children: Child[]) {
  el.replaceChildren();
  for (const c of children) {
    if (c === null || c === undefined || c === false) continue;
    el.append(typeof c === 'string' || typeof c === 'number' ? document.createTextNode(String(c)) : c);
  }
}

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

export function toggle(checked: boolean, onChange: (v: boolean) => void | Promise<void>, label?: string): HTMLLabelElement {
  const input = h('input', { type: 'checkbox', checked, attrs: { role: 'switch' } });
  input.onchange = async () => {
    input.disabled = true;
    try {
      await onChange(input.checked);
    } finally {
      input.disabled = false;
    }
  };
  return h('label', { class: 'switch', ariaLabel: label }, input, h('span', { class: 'track' }));
}

let toastTimer: ReturnType<typeof setTimeout> | null = null;
export function toast(message: string, tone: 'ok' | 'error' = 'ok') {
  let t = document.getElementById('toast');
  if (!t) {
    t = h('div', { id: 'toast', role: 'status' });
    document.body.append(t);
  }
  t.textContent = message;
  t.dataset.tone = tone;
  t.classList.add('show');
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t?.classList.remove('show'), tone === 'error' ? 6000 : 3500);
}
