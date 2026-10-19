export interface Mounted {
  /** Called when the shared state changed (status, servers, rules). */
  update(): void;
  unmount?(): void;
}

export interface Section {
  id: string;
  title: string;
  subtitle: string;
  icon: string;
  mount(el: HTMLElement): Mounted;
}
