import type { BackupPreview, IdeCredentials, RestoreReport, Settings } from '../../../../protocol/types';
import { call, download, exportBackup, request } from '../../shared/client';
import { clear, confirmButton, h, toast, toggle } from '../../shared/dom';
import { dateTime } from '../../shared/format';
import { applyTheme, setTheme, type ThemeChoice } from '../../shared/theme';
import { ctx, isReady, refreshList, refreshRules } from '../context';
import type { Section } from '../section';

async function save(patch: Partial<Settings>): Promise<boolean> {
  const r = await request('setSettings', patch);
  if (!r.ok) {
    toast(r.error.message, 'error');
    return false;
  }
  if ((r.result as { reconnectRequired?: boolean }).reconnectRequired) toast('Saved. Reconnect to apply.');
  return true;
}

function line(title: string, text: string, control: HTMLElement): HTMLElement {
  return h('div', { class: 'setting' }, h('div', { class: 'text' }, h('b', { text: title }), h('span', { text })), control);
}

export const settingsSection: Section = {
  id: 'settings',
  title: 'Settings',
  subtitle: 'How PerRoute connects, protects and updates, and your backups.',
  icon: 'settings',
  mount(el) {
    let theme: ThemeChoice = 'system';
    let creds: IdeCredentials | null = null;
    let showPass = false;
    let restoreText: string | null = null;
    let preview: BackupPreview | null = null;
    let previewError = '';

    const general = h('section', { class: 'card' });
    const connection = h('section', { class: 'card' });
    const local = h('section', { class: 'card stack' });
    const backup = h('section', { class: 'card stack' });
    const advanced = h('section', { class: 'card' });
    el.append(h('div', { class: 'grid2' }, general, connection), local, backup, advanced);

    function renderGeneral() {
      const s = ctx.app?.settings;
      const themeSeg = h('div', { class: 'segmented' });
      for (const [v, t] of [
        ['system', 'System'],
        ['light', 'Light'],
        ['dark', 'Dark'],
      ] as const) {
        themeSeg.append(h('button', { text: t, attrs: { 'aria-pressed': String(theme === v) }, on: { click: async () => ((theme = v), await setTheme(v), renderGeneral()) } }));
      }
      const hours = h('select');
      for (const [v, t] of [
        [0, 'Never'],
        [6, 'Every 6 hours'],
        [12, 'Every 12 hours'],
        [24, 'Every day'],
        [72, 'Every 3 days'],
        [168, 'Every week'],
      ] as const) hours.append(h('option', { value: String(v), text: t }));
      hours.value = String(s?.subscriptionUpdateHours ?? 24);
      if (!hours.value) hours.value = '24';
      hours.onchange = () => void save({ subscriptionUpdateHours: Number(hours.value) });
      clear(
        general,
        h('h2', { text: 'General', style: { marginBottom: '6px' } }),
        line('Appearance', 'Follows the system, or always light or dark.', themeSeg),
        line('Update subscriptions', 'Refresh subscriptions in the background (a shorter interval asked by the provider is used).', hours),
        line('Count traffic', 'Totals per day for the Traffic page. Sites are never recorded.', toggle(s?.trafficStats ?? true, (v) => void save({ trafficStats: v }))),
      );
    }

    function renderConnection() {
      const s = ctx.app?.settings;
      if (!s) return clear(connection, h('h2', { text: 'Connection' }), h('p', { class: 'muted small', text: 'Loading…' }));
      clear(
        connection,
        h('h2', { text: 'Connection', style: { marginBottom: '6px' } }),
        line('Connect when the browser starts', 'Reconnects to the last server.', toggle(s.autoConnect, (v) => void save({ autoConnect: v }))),
        line('Fastest server on connect', 'Tests the best servers and connects to the fastest.', toggle(s.fastestOnConnect, (v) => void save({ fastestOnConnect: v }))),
        line('Automatic failover', 'Move to a working server when the current one stops responding.', toggle(s.autoFailover, (v) => void save({ autoFailover: v }))),
        line('Kill switch', 'If the tunnel drops, tunnel sites stay offline until it is back.', toggle(s.killSwitch, (v) => void save({ killSwitch: v }))),
        line('WebRTC protection', 'Stops WebRTC from revealing your real address while connected.', toggle(s.webrtcProtection, (v) => void save({ webrtcProtection: v }))),
      );
    }

    async function loadCreds() {
      if (!isReady() || !ctx.app?.settings?.ideAuth) {
        creds = null;
        return renderLocal();
      }
      const r = await request('getIdeCredentials', {});
      creds = r.ok ? (r.result as IdeCredentials) : null;
      renderLocal();
    }

    function renderLocal() {
      const s = ctx.app?.settings;
      if (!s) return clear(local, h('h2', { text: 'Local proxy for other apps' }));
      const httpPort = h('input', { type: 'number', value: String(s.jetbrainsHttpPort), style: { width: '110px' } });
      const socksPort = h('input', { type: 'number', value: String(s.jetbrainsSocksPort), style: { width: '110px' } });
      const savePorts = async () => {
        const http = Number(httpPort.value);
        const socks = Number(socksPort.value);
        if (![http, socks].every((p) => Number.isInteger(p) && p >= 1024 && p <= 65535) || http === socks) return toast('Ports must be different numbers between 1024 and 65535.', 'error');
        if (await save({ jetbrainsHttpPort: http, jetbrainsSocksPort: socks })) toast('Ports saved.');
      };
      const copy = async (text: string, what: string) => {
        await navigator.clipboard.writeText(text);
        toast(`${what} copied.`);
      };
      clear(
        local,
        h('h2', { text: 'Local proxy for other apps' }),
        h('p', { class: 'muted small', text: 'IDEs and other programs with proxy settings can use the tunnel through these local ports. Other programs are not affected.' }),
        h(
          'div',
          null,
          line('Enabled', 'Listen on 127.0.0.1 only.', toggle(s.jetbrainsEnabled, (v) => void save({ jetbrainsEnabled: v }))),
          line('When not connected', 'Keep the ports open and connect directly, so programs keep working.', toggle(s.passthroughWhenDisconnected, (v) => void save({ passthroughWhenDisconnected: v }))),
          line('Require a password', 'Without it, any program on this computer can use the ports.', toggle(s.ideAuth, async (v) => ((await save({ ideAuth: v })) ? void loadCreds() : undefined))),
        ),
        h('div', { class: 'row wrap' }, h('label', { class: 'field' }, h('span', { text: 'HTTP port' }), httpPort), h('label', { class: 'field' }, h('span', { text: 'SOCKS5 port' }), socksPort), h('button', { class: 'btn', text: 'Save ports', style: { alignSelf: 'flex-end' }, on: { click: () => void savePorts() } })),
        s.ideAuth && creds
          ? h(
              'div',
              { class: 'banner info' },
              h(
                'div',
                { class: 'grow' },
                h('dl', { class: 'kv' }, h('dt', { text: 'Username' }), h('dd', { class: 'mono', text: creds.username }), h('dt', { text: 'Password' }), h('dd', { class: 'mono', text: showPass ? creds.password : '••••••••••••' })),
              ),
              h(
                'div',
                { class: 'row' },
                h('button', { class: 'btn small', text: showPass ? 'Hide' : 'Show', on: { click: () => ((showPass = !showPass), renderLocal()) } }),
                h('button', { class: 'btn small', text: 'Copy user', on: { click: () => void copy(creds!.username, 'Username') } }),
                h('button', { class: 'btn small', text: 'Copy password', on: { click: () => void copy(creds!.password, 'Password') } }),
                confirmButton('New password', 'Replace it?', 'btn small', async () => {
                  creds = await call<IdeCredentials>('regenerateIdeCredentials', {});
                  toast(creds.reconnectRequired ? 'New password created. Reconnect, then update your programs.' : 'New password created. Update it in your programs.');
                  renderLocal();
                }),
              ),
            )
          : null,
      );
    }

    // ---------------------------------------------------------------- backup
    const pass = h('input', { type: 'password', placeholder: 'Password (recommended)', style: { width: '220px' } });
    const parts = { servers: true, rules: true, settings: true };
    const fileInput = h('input', { type: 'file', attrs: { accept: '.json,application/json' }, hidden: true });
    const restorePass = h('input', { type: 'password', placeholder: 'Backup password', style: { width: '220px' } });

    fileInput.onchange = async () => {
      const f = fileInput.files?.[0];
      fileInput.value = '';
      if (!f) return;
      if (f.size > 8 * 1024 * 1024) return toast('The file is too large to be a backup.', 'error');
      restoreText = await f.text();
      await loadPreview();
    };

    async function loadPreview() {
      if (!restoreText) return;
      const r = await request('previewBackup', { text: restoreText, password: restorePass.value || undefined });
      preview = r.ok ? (r.result as BackupPreview) : null;
      previewError = r.ok ? '' : r.error.message;
      renderBackup();
    }

    async function doExport() {
      if (!parts.servers && !parts.rules && !parts.settings) return toast('Choose at least one part.', 'error');
      if (!pass.value && parts.servers && !confirm('Without a password the file holds your server credentials in plain text. Continue?')) return;
      try {
        const b = await exportBackup({ password: pass.value || undefined, ...parts });
        download(b.text, b.fileName);
        toast(b.encrypted ? 'Encrypted backup saved.' : 'Backup saved.');
      } catch (e) {
        toast((e as Error).message, 'error');
      }
    }

    async function doRestore(mode: 'merge' | 'replace') {
      if (!restoreText) return;
      try {
        const r = await call<RestoreReport>('restoreBackup', { text: restoreText, password: restorePass.value || undefined, mode });
        toast(`Restored: ${r.serversAdded} servers added, ${r.serversUpdated} updated, ${r.rules} rules, ${r.profiles} profiles${r.serversRejected ? `, ${r.serversRejected} skipped` : ''}.`);
        restoreText = null;
        preview = null;
        await Promise.all([refreshList(), refreshRules()]);
        renderBackup();
      } catch (e) {
        toast((e as Error).message, 'error');
      }
    }

    function renderBackup() {
      const check = (key: keyof typeof parts, label: string) => {
        const c = h('input', { type: 'checkbox', checked: parts[key] });
        c.onchange = () => (parts[key] = c.checked);
        return h('label', { class: 'row small' }, c, label);
      };
      restorePass.onkeydown = (e) => e.key === 'Enter' && void loadPreview();
      clear(
        backup,
        h('h2', { text: 'Backup and restore' }),
        h('p', { class: 'muted small', text: 'A backup moves servers, subscriptions, rules, profiles and settings to another computer or browser.' }),
        h('div', { class: 'row wrap' }, check('servers', 'Servers and subscriptions'), check('rules', 'Rules and profiles'), check('settings', 'Settings')),
        h('div', { class: 'row wrap' }, pass, h('button', { class: 'btn primary', text: 'Save backup…', disabled: !isReady(), on: { click: () => void doExport() } })),
        h('hr', { style: { border: 'none', borderTop: '1px solid var(--line)', width: '100%' } }),
        h('div', { class: 'row wrap' }, h('button', { class: 'btn', text: 'Open a backup…', disabled: !isReady(), on: { click: () => fileInput.click() } }), restoreText ? restorePass : null, restoreText ? h('button', { class: 'btn small', text: 'Unlock', on: { click: () => void loadPreview() } }) : null),
        previewError ? h('p', { class: 'small', style: { color: 'var(--danger)' }, text: previewError }) : null,
        preview
          ? h(
              'div',
              { class: 'banner info' },
              h(
                'div',
                { class: 'grow small' },
                h('b', { text: `Backup from ${dateTime(preview.createdAt)}${preview.encrypted ? ' (encrypted)' : ''}` }),
                h('div', { text: `${preview.servers} servers, ${preview.subscriptions} subscriptions, ${preview.rules} rules, ${preview.profiles} profiles${preview.hasSettings ? ', settings' : ''}.` }),
                h('div', { class: 'muted', text: 'Merge adds to what you have. Replace removes your current servers, rules and profiles first. Either way you can undo it.' }),
              ),
              h('div', { class: 'stack' }, h('button', { class: 'btn small primary', text: 'Merge', on: { click: () => void doRestore('merge') } }), confirmButton('Replace', 'Replace all?', 'btn small danger', () => doRestore('replace'))),
            )
          : null,
        ctx.list.canUndoRestore
          ? h(
              'div',
              { class: 'row' },
              h('span', { class: 'muted small grow', text: 'The last restore can be undone.' }),
              confirmButton('Undo restore', 'Undo it?', 'btn small', async () => {
                try {
                  await call('undoRestore', {});
                  toast('Restore undone.');
                  await Promise.all([refreshList(), refreshRules()]);
                } catch (e) {
                  toast((e as Error).message, 'error');
                }
              }),
            )
          : null,
        fileInput,
      );
    }

    function renderAdvanced() {
      const s = ctx.app?.settings;
      clear(
        advanced,
        h('h2', { text: 'Advanced', style: { marginBottom: '6px' } }),
        line('Detailed logging', 'Writes more detail to the runtime log, for troubleshooting.', toggle(s?.debugLogging ?? false, (v) => void save({ debugLogging: v }))),
        line('Private-network subscriptions', 'Allow subscription addresses on your local or company network.', toggle(s?.allowPrivateSubscriptionHosts ?? false, (v) => void save({ allowPrivateSubscriptionHosts: v }))),
        line(
          'Remove everything',
          'Deletes all servers, subscriptions, credentials, rules and history from this computer.',
          confirmButton('Remove all', 'Remove everything?', 'btn danger', async () => {
            await call('resetAll', { confirm: true });
            toast('Everything was removed.');
            await Promise.all([refreshList(), refreshRules()]);
          }),
        ),
      );
    }

    function update() {
      renderGeneral();
      renderConnection();
      renderLocal();
      renderBackup();
      renderAdvanced();
    }
    void applyTheme().then((t) => {
      theme = t;
      renderGeneral();
    });
    update();
    void loadCreds();
    return {
      update() {
        // Keep typed passwords and ports: only re-render the parts without text fields.
        renderGeneral();
        renderConnection();
        renderAdvanced();
        if (!creds && ctx.app?.settings?.ideAuth) void loadCreds();
      },
    };
  },
};
