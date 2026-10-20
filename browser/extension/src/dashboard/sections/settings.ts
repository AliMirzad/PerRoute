import type { BackupPreview, IdeCredentials, RestoreReport, Settings } from '../../../../protocol/types';
import { call, download, exportBackup, request } from '../../shared/client';
import { actionButton, confirmButton, confirmDialog, copyText, h, region, select, toast, toggle } from '../../shared/dom';
import { dateTime, plural } from '../../shared/format';
import { applyTheme, setTheme, type ThemeChoice } from '../../shared/theme';
import { ctx, isReady, refreshList, refreshRules } from '../context';
import type { Section } from '../section';
import { saveSettings as save, settingLine as line } from './connect';

type Key = keyof Settings;

export const settingsSection: Section = {
  id: 'settings',
  title: 'Settings',
  subtitle: 'How PerRoute connects, protects and updates, and your backups.',
  icon: 'settings',
  mount(el) {
    let theme: ThemeChoice = 'system';
    let autoSpeed = true;
    let shortcuts: chrome.commands.Command[] = [];
    let creds: IdeCredentials | null = null;
    let showPass = false;
    let restoreText: string | null = null;
    let restoreName = '';
    let preview: BackupPreview | null = null;
    let previewError = '';
    let report: RestoreReport | null = null;

    const s = () => ctx.app?.settings ?? null;
    const sw = (key: Key, label: string) => toggle(!!s()?.[key], (v) => save({ [key]: v } as Partial<Settings>), label, !s());

    const general = region(h('section', { class: 'card' }), () => {
      const themeSeg = h('div', { class: 'segmented', role: 'group' });
      for (const [v, t] of [
        ['system', 'System'],
        ['light', 'Light'],
        ['dark', 'Dark'],
      ] as const) {
        themeSeg.append(h('button', { type: 'button', text: t, attrs: { 'aria-pressed': String(theme === v) }, on: { click: async () => ((theme = v), await setTheme(v), general.render(true)) } }));
      }
      const hours = s()?.subscriptionUpdateHours ?? 24;
      const opts: [string, string][] = [
        ['0', 'Never'],
        ['6', 'Every 6 hours'],
        ['12', 'Every 12 hours'],
        ['24', 'Every day'],
        ['72', 'Every 3 days'],
        ['168', 'Every week'],
      ];
      if (!opts.some(([v]) => Number(v) === hours)) opts.push([String(hours), `Every ${hours} hours`]);
      return [
        h('h2', { text: 'General' }),
        line('Appearance', 'Follows the system, or always light or dark.', themeSeg),
        line('Update subscriptions', 'In the background; a shorter interval asked by the provider is used. Paused while Data Saver is on.', select(opts, String(hours), (v) => void save({ subscriptionUpdateHours: Number(v) }), { disabled: !s(), ariaLabel: 'Update subscriptions' })),
        line('Count traffic', 'Totals per day for the Traffic page. Sites are never recorded.', sw('trafficStats', 'Count traffic')),
        line(
          'Measure speed after tests',
          'After a real test, measure the download speed of the 5 best servers.',
          toggle(autoSpeed, async (v) => {
            autoSpeed = v;
            await chrome.storage.local.set({ autoSpeed: v });
          }, 'Measure speed after tests'),
        ),
        line(
          'Keyboard shortcuts',
          shortcuts.length ? shortcuts.filter((c) => c.description).map((c) => `${c.description}: ${c.shortcut || 'not set'}`).join(' · ') : 'Connect, switch the current site and more from the keyboard.',
          h('button', { class: 'btn small', text: 'Change…', on: { click: () => void chrome.tabs.create({ url: 'chrome://extensions/shortcuts' }) } }),
        ),
      ];
    }, () => [theme, autoSpeed, shortcuts, s()?.subscriptionUpdateHours, s()?.trafficStats, !!s()]);

    const connection = region(h('section', { class: 'card' }), () => [
      h('h2', { text: 'Connection' }),
      line('Connect when the browser starts', 'Reconnects to the last server.', sw('autoConnect', 'Connect when the browser starts')),
      line('Fastest server on connect', 'Tests the best servers and connects to the fastest.', sw('fastestOnConnect', 'Fastest server on connect')),
      line('Automatic failover', 'Move to a working server when the current one stops responding.', sw('autoFailover', 'Automatic failover')),
      line('Kill switch', 'If the tunnel drops, tunnel sites stay offline until it is back. Direct sites keep working.', sw('killSwitch', 'Kill switch')),
      line('WebRTC protection', 'Stops WebRTC from revealing your real address while connected.', sw('webrtcProtection', 'WebRTC protection')),
    ], () => [s()?.autoConnect, s()?.fastestOnConnect, s()?.autoFailover, s()?.killSwitch, s()?.webrtcProtection, !!s()]);

    // ---------------------------------------------------------------- local proxy for other apps
    const httpPort = h('input', { type: 'number', attrs: { min: '1024', max: '65535' }, style: { width: '110px' }, ariaLabel: 'HTTP port' });
    const socksPort = h('input', { type: 'number', attrs: { min: '1024', max: '65535' }, style: { width: '110px' }, ariaLabel: 'SOCKS5 port' });
    const syncPorts = () => {
      const x = s();
      if (!x) return;
      if (document.activeElement !== httpPort) httpPort.value = String(x.jetbrainsHttpPort);
      if (document.activeElement !== socksPort) socksPort.value = String(x.jetbrainsSocksPort);
    };
    async function savePorts() {
      const http = Number(httpPort.value);
      const socks = Number(socksPort.value);
      if (![http, socks].every((p) => Number.isInteger(p) && p >= 1024 && p <= 65535) || http === socks) throw new Error('Ports must be two different numbers between 1024 and 65535.');
      if (await save({ jetbrainsHttpPort: http, jetbrainsSocksPort: socks })) toast('Ports saved.');
    }

    async function loadCreds() {
      if (!isReady() || !s()?.ideAuth) creds = null;
      else {
        const r = await request('getIdeCredentials', {});
        creds = r.ok ? (r.result as IdeCredentials) : null;
      }
      local.render();
    }

    const local = region(h('section', { class: 'card stack' }), () => {
      const x = s();
      syncPorts();
      return [
        h('h2', { text: 'Local proxy for other apps' }),
        h('p', { class: 'muted small', text: 'IDEs and other programs with proxy settings can use the tunnel through these local ports. Other programs are not affected.' }),
        h(
          'div',
          null,
          line('Enabled', 'Listens on 127.0.0.1 only.', sw('jetbrainsEnabled', 'Local proxy enabled')),
          line('When not connected', 'Keep the ports open and connect directly, so programs keep working.', sw('passthroughWhenDisconnected', 'Direct when not connected')),
          line(
            'Require a password',
            'Without it, any program on this computer can use the ports.',
            toggle(!!x?.ideAuth, async (v) => {
              if (!(await save({ ideAuth: v }))) return false;
              void loadCreds();
              return true;
            }, 'Require a password', !x),
          ),
        ),
        h('div', { class: 'row wrap', style: { alignItems: 'flex-end' } }, h('label', { class: 'field' }, h('span', { text: 'HTTP port' }), httpPort), h('label', { class: 'field' }, h('span', { text: 'SOCKS5 port' }), socksPort), actionButton('Save ports', 'btn', savePorts, 'Saving…', { disabled: !x })),
        x?.ideAuth && creds
          ? h(
              'div',
              { class: 'banner info' },
              h('dl', { class: 'kv grow' }, h('dt', { text: 'Username' }), h('dd', { class: 'mono', text: creds.username }), h('dt', { text: 'Password' }), h('dd', { class: 'mono', text: showPass ? creds.password : '••••••••••••' })),
              h(
                'div',
                { class: 'row wrap', style: { justifyContent: 'flex-end' } },
                h('button', { class: 'btn small', text: showPass ? 'Hide' : 'Show', on: { click: () => ((showPass = !showPass), local.render(true)) } }),
                h('button', { class: 'btn small', text: 'Copy user', on: { click: () => void copyText(creds!.username, 'Username') } }),
                h('button', { class: 'btn small', text: 'Copy password', on: { click: () => void copyText(creds!.password, 'Password') } }),
                confirmButton('New password', 'Replace it?', 'btn small', async () => {
                  creds = await call<IdeCredentials>('regenerateIdeCredentials', {});
                  toast(creds.reconnectRequired ? 'New password created. Reconnect, then update your programs.' : 'New password created. Update it in your programs.');
                  local.render(true);
                }),
              ),
            )
          : null,
      ];
    }, () => [s()?.jetbrainsEnabled, s()?.passthroughWhenDisconnected, s()?.ideAuth, s()?.jetbrainsHttpPort, s()?.jetbrainsSocksPort, creds, showPass, !!s()]);

    // ---------------------------------------------------------------- backup
    const pass = h('input', { type: 'password', placeholder: 'Password (recommended)', style: { width: '220px' }, ariaLabel: 'Backup password', attrs: { autocomplete: 'new-password' } });
    const parts = { servers: true, rules: true, settings: true };
    const fileInput = h('input', { type: 'file', attrs: { accept: '.json,application/json' }, hidden: true });
    const restorePass = h('input', { type: 'password', placeholder: 'Backup password', style: { width: '220px' }, ariaLabel: 'Password of the backup', attrs: { autocomplete: 'off' } });
    restorePass.onkeydown = (e) => e.key === 'Enter' && void loadPreview();

    fileInput.onchange = async () => {
      const f = fileInput.files?.[0];
      fileInput.value = '';
      if (!f) return;
      if (f.size > 8 * 1024 * 1024) return toast('The file is too large to be a backup.', 'error');
      restoreText = await f.text();
      restoreName = f.name;
      restorePass.value = '';
      report = null;
      await loadPreview();
    };

    async function loadPreview() {
      if (!restoreText) return;
      const r = await request('previewBackup', { text: restoreText, password: restorePass.value || undefined });
      preview = r.ok ? (r.result as BackupPreview) : null;
      previewError = r.ok ? '' : r.error.message;
      backup.render(true);
      if (!r.ok && /password/i.test(r.error.message)) restorePass.focus();
    }

    async function doExport() {
      if (!parts.servers && !parts.rules && !parts.settings) throw new Error('Choose at least one part.');
      if (!pass.value && parts.servers && !(await confirmDialog('Save without a password?', 'Without a password the file holds your server links and credentials in plain text. Anyone with the file can use your servers.', 'Save without a password', true))) return;
      const b = await exportBackup({ password: pass.value || undefined, ...parts });
      download(b.text, b.fileName);
      pass.value = '';
      toast(b.encrypted ? 'Encrypted backup saved.' : 'Backup saved.');
    }

    async function doRestore(mode: 'merge' | 'replace') {
      if (!restoreText) return;
      report = await call<RestoreReport>('restoreBackup', { text: restoreText, password: restorePass.value || undefined, mode });
      toast('Backup restored.');
      restoreText = null;
      preview = null;
      restorePass.value = '';
      await Promise.all([refreshList(), refreshRules()]);
      backup.render(true);
    }

    const backup = region(h('section', { class: 'card stack' }), () => {
      const check = (key: keyof typeof parts, label: string) => {
        const c = h('input', { type: 'checkbox', checked: parts[key] });
        c.onchange = () => (parts[key] = c.checked);
        return h('label', { class: 'row small check-label' }, c, label);
      };
      return [
        h('h2', { text: 'Backup and restore' }),
        h('p', { class: 'muted small', text: 'A backup moves servers, subscriptions, rules, profiles and settings to another computer or browser.' }),
        h('div', { class: 'row wrap' }, check('servers', 'Servers and subscriptions'), check('rules', 'Rules and profiles'), check('settings', 'Settings')),
        h('div', { class: 'row wrap' }, pass, actionButton('Save backup…', 'btn primary', doExport, 'Saving…', { disabled: !isReady() })),
        h('hr'),
        h(
          'div',
          { class: 'row wrap' },
          h('button', { class: 'btn', text: 'Open a backup…', disabled: !isReady(), on: { click: () => fileInput.click() } }),
          restoreText ? h('span', { class: 'muted small ellipsis', text: restoreName }) : null,
          restoreText && (preview?.encrypted || previewError) ? restorePass : null,
          restoreText && (preview?.encrypted || previewError) ? h('button', { class: 'btn small', text: 'Unlock', on: { click: () => void loadPreview() } }) : null,
        ),
        previewError ? h('p', { class: 'small error-text', text: previewError }) : null,
        preview
          ? h(
              'div',
              { class: 'banner info' },
              h(
                'div',
                { class: 'grow small' },
                h('b', { text: `Backup from ${dateTime(preview.createdAt)}${preview.encrypted ? ' (encrypted)' : ''}` }),
                h('div', { text: `${plural(preview.servers, 'server')}, ${plural(preview.subscriptions, 'subscription')}, ${plural(preview.rules, 'rule')}, ${plural(preview.profiles, 'profile')}${preview.hasSettings ? ', settings' : ''}.` }),
                h('div', { class: 'muted', text: 'Merge adds to what you have. Replace removes your current servers, rules and profiles first. Either way you can undo it.' }),
              ),
              h('div', { class: 'stack', style: { gap: '6px' } }, actionButton('Merge', 'btn small primary', () => doRestore('merge'), 'Restoring…'), confirmButton('Replace', 'Replace all?', 'btn small danger', () => doRestore('replace').catch((e: Error) => toast(e.message, 'error')))),
            )
          : null,
        report
          ? h(
              'div',
              { class: 'banner info' },
              h(
                'div',
                { class: 'grow small' },
                h('b', { text: 'Restored' }),
                h('div', { text: `${report.serversAdded} servers added, ${report.serversUpdated} updated${report.serversRejected ? `, ${report.serversRejected} skipped` : ''}; ${plural(report.subscriptions, 'subscription')}, ${plural(report.rules, 'rule')}, ${plural(report.profiles, 'profile')}${report.settings ? ', settings' : ''}.` }),
                ...report.warnings.slice(0, 5).map((w) => h('div', { class: 'warn-text', text: w })),
              ),
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
                  report = null;
                  toast('Restore undone.');
                  await Promise.all([refreshList(), refreshRules()]);
                } catch (e) {
                  toast((e as Error).message, 'error');
                }
              }),
            )
          : null,
        fileInput,
      ];
    }, () => [isReady(), ctx.list.canUndoRestore, restoreText !== null, preview, previewError, report]);

    const advanced = region(h('section', { class: 'card' }), () => [
      h('h2', { text: 'Advanced' }),
      line('Detailed logging', 'Writes more detail to the runtime log, for troubleshooting.', sw('debugLogging', 'Detailed logging')),
      line('Private-network subscriptions', 'Allow subscription addresses on your local or company network.', sw('allowPrivateSubscriptionHosts', 'Private-network subscriptions')),
      line(
        'Remove everything',
        'Deletes all servers, subscriptions, credentials, rules and history from this computer.',
        confirmButton('Remove all', 'Remove everything?', 'btn danger', async () => {
          try {
            await call('resetAll', { confirm: true });
            toast('Everything was removed.');
            await Promise.all([refreshList(), refreshRules()]);
          } catch (e) {
            toast((e as Error).message, 'error');
          }
        }),
      ),
    ], () => [s()?.debugLogging, s()?.allowPrivateSubscriptionHosts, !!s()]);

    el.append(h('div', { class: 'grid2' }, general.el, connection.el), local.el, backup.el, advanced.el);

    function update() {
      general.render();
      connection.render();
      local.render();
      syncPorts();
      backup.render();
      advanced.render();
      if (!creds && s()?.ideAuth && isReady()) void loadCreds();
    }
    update();
    void applyTheme().then((t) => {
      theme = t;
      general.render();
    });
    void chrome.storage.local
      .get('autoSpeed')
      .then((x) => {
        autoSpeed = x.autoSpeed !== false;
        general.render();
      })
      .catch(() => undefined);
    void chrome.commands
      ?.getAll()
      .then((c) => {
        shortcuts = c.filter((x) => x.name !== '_execute_action' || x.shortcut);
        general.render();
      })
      .catch(() => undefined);
    void loadCreds();
    return { update };
  },
};
