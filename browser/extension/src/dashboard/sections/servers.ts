import type { ImportResult, ServerSummary, SiteTiming, SubscriptionResult, SubscriptionSummary, TestResult } from '../../../../protocol/types';
import { call, request } from '../../shared/client';
import { clear, confirmButton, h, toast } from '../../shared/dom';
import { ago, bytes, date, dateTime, mbps, plural } from '../../shared/format';
import { icon } from '../../shared/icons';
import { protocolLine } from '../../shared/view';
import { decodeQrFromBlob, looksLikeProxyConfig } from '../../popup/qr';
import { activeServerId, ctx, isConnected, isReady, onEvent, refreshList } from '../context';
import type { Section } from '../section';

type Sort = 'list' | 'name' | 'latency' | 'score';

function summarizeImport(r: ImportResult): string {
  const parts = [`${r.added} added, ${r.updated} updated`];
  if (r.rejected) parts.push(`${r.rejected} rejected${r.errors[0] ? ` (${r.errors[0].message})` : ''}`);
  if (r.unsupported) parts.push(`${r.unsupported} unsupported skipped`);
  return parts.join(' · ');
}

function dialog(title: string, body: HTMLElement, actions: HTMLElement[]): HTMLDialogElement {
  const d = h('dialog', null, h('div', { class: 'stack' }, h('h2', { text: title }), body, h('div', { class: 'row', style: { justifyContent: 'flex-end' } }, ...actions)));
  d.addEventListener('close', () => d.remove());
  document.body.append(d);
  d.showModal();
  return d;
}

function prompt(title: string, label: string, value: string, onSave: (v: string) => Promise<void>) {
  const input = h('input', { type: 'text', value, style: { width: '100%' } });
  const save = h('button', { class: 'btn primary', text: 'Save' });
  const d = dialog(title, h('label', { class: 'field' }, h('span', { text: label }), input), [h('button', { class: 'btn', text: 'Cancel', on: { click: () => d.close() } }), save]);
  const go = async () => {
    save.disabled = true;
    try {
      await onSave(input.value.trim());
      d.close();
    } catch (e) {
      toast((e as Error).message, 'error');
      save.disabled = false;
    }
  };
  save.onclick = () => void go();
  input.onkeydown = (e) => e.key === 'Enter' && void go();
  input.focus();
  input.select();
}

function compareDialog(s: ServerSummary) {
  const urls = h('textarea', { attrs: { rows: '4' }, style: { width: '100%' }, placeholder: 'https://www.youtube.com\nhttps://www.wikipedia.org' });
  urls.value = 'https://www.google.com\nhttps://www.youtube.com\nhttps://www.wikipedia.org';
  const out = h('div', { class: 'stack' });
  const run = h('button', { class: 'btn primary', text: 'Compare' });
  const d = dialog(
    `Compare ${s.name} with the live route`,
    h(
      'div',
      { class: 'stack' },
      h('p', { class: 'muted small', text: 'The same sites are opened through your current connection and through this server, side by side. Your connection is not changed.' }),
      h('label', { class: 'field' }, h('span', { text: 'Sites (https only, up to 8)' }), urls),
      out,
    ),
    [h('button', { class: 'btn', text: 'Close', on: { click: () => d.close() } }), run],
  );
  run.onclick = async () => {
    run.disabled = true;
    clear(out, h('p', { class: 'muted small', text: 'Opening the sites through both routes…' }));
    try {
      const list = urls.value.split(/\s+/).filter(Boolean).slice(0, 8);
      const r = await call<{ live: SiteTiming[]; candidate: SiteTiming[] }>('compare', { candidateId: s.id, urls: list });
      const cell = (t: SiteTiming) => h('td', null, t.ok ? h('span', { class: 'chip ok', text: `${t.ms} ms` }) : h('span', { class: 'chip fail', text: t.error ?? `HTTP ${t.status}` }));
      const table = h('table', { class: 'table' }, h('tr', null, h('th', { text: 'Site' }), h('th', { text: 'Live route' }), h('th', { text: s.name })));
      r.live.forEach((t, i) => table.append(h('tr', null, h('td', { class: 'ellipsis', text: new URL(t.url).hostname }), cell(t), cell(r.candidate[i]))));
      clear(out, table);
    } catch (e) {
      clear(out, h('p', { class: 'small', style: { color: 'var(--danger)' }, text: (e as Error).message }));
    }
    run.disabled = false;
  };
}

export const serversSection: Section = {
  id: 'servers',
  title: 'Servers',
  subtitle: 'Subscriptions, links and QR codes. Test servers and keep your favorites on top.',
  icon: 'servers',
  mount(el) {
    let addMode: 'paste' | 'subscription' | 'qr' = 'subscription';
    let search = '';
    let group = 'all';
    let sort: Sort = 'list';
    const testing = new Set<string>();
    const speeds = new Map<string, string>();
    let addResult = '';
    let addError = false;

    const addCard = h('section', { class: 'card stack' });
    const subsWrap = h('div', { class: 'grid2' });
    const serversCard = h('section', { class: 'card stack' });
    const tableWrap = h('div', { class: 'table-wrap' });
    el.append(addCard, subsWrap, serversCard);

    // ---------------------------------------------------------------- add servers
    const pasteArea = h('textarea', { attrs: { rows: '4' }, style: { width: '100%' }, placeholder: 'vless://…  vmess://…  trojan://…  ss://…  or Xray JSON' });
    const subName = h('input', { type: 'text', placeholder: 'Name (optional)' });
    const subUrl = h('input', { type: 'url', placeholder: 'https://…', style: { flex: '1' } });
    const qrFile = h('input', { type: 'file', attrs: { accept: 'image/*' }, hidden: true });
    const textFile = h('input', { type: 'file', attrs: { accept: '.txt,.json,text/plain,application/json' }, hidden: true });

    async function importText(text: string, source: 'paste' | 'qr' | 'file') {
      if (!text.trim()) return setResult('Nothing to import.', true);
      setResult('Importing…', false);
      const r = await request('importText', { text, source });
      if (r.ok) {
        setResult(summarizeImport(r.result as ImportResult), false);
        pasteArea.value = '';
        await refreshList();
      } else setResult(r.error.message, true);
    }

    async function importQr(blob: Blob) {
      try {
        setResult('Reading the QR code…', false);
        const text = await decodeQrFromBlob(blob);
        if (!text) return setResult('No QR code found in the image.', true);
        if (!looksLikeProxyConfig(text)) return setResult('This QR code does not contain a server configuration.', true);
        await importText(text, 'qr');
      } catch (e) {
        setResult(`Could not read the image: ${(e as Error).message}`, true);
      }
    }

    async function addSubscription() {
      const url = subUrl.value.trim();
      if (!url) return setResult('Enter the subscription address.', true);
      setResult('Downloading the subscription…', false);
      const r = await request('addSubscription', { name: subName.value.trim(), url });
      if (r.ok) {
        const s = r.result as SubscriptionResult;
        setResult(`Subscription added: ${plural(s.added, 'server')}${s.rejected ? `, ${s.rejected} rejected` : ''}${s.unsupported ? `, ${s.unsupported} unsupported skipped` : ''}.`, false);
        subUrl.value = '';
        subName.value = '';
        await refreshList();
      } else setResult(r.error.message, true);
    }

    function setResult(text: string, error: boolean) {
      addResult = text;
      addError = error;
      renderAdd();
    }

    qrFile.onchange = () => {
      const f = qrFile.files?.[0];
      if (f) void importQr(f);
      qrFile.value = '';
    };
    textFile.onchange = async () => {
      const f = textFile.files?.[0];
      if (f) {
        if (f.size > 5 * 1024 * 1024) setResult('The file is too large (5 MB at most).', true);
        else await importText(await f.text(), 'file');
      }
      textFile.value = '';
    };
    const onPaste = (e: ClipboardEvent) => {
      if (addMode !== 'qr') return;
      const blob = [...(e.clipboardData?.items ?? [])].find((i) => i.type.startsWith('image/'))?.getAsFile();
      if (blob) {
        e.preventDefault();
        void importQr(blob);
      }
    };
    document.addEventListener('paste', onPaste);

    function renderAdd() {
      const seg = h('div', { class: 'segmented' });
      for (const [m, label] of [
        ['subscription', 'Subscription'],
        ['paste', 'Links or JSON'],
        ['qr', 'QR code'],
      ] as const) {
        seg.append(h('button', { text: label, attrs: { 'aria-pressed': String(addMode === m) }, on: { click: () => ((addMode = m), (addResult = ''), renderAdd()) } }));
      }
      let panel: HTMLElement;
      if (addMode === 'subscription') {
        panel = h('div', { class: 'row wrap' }, subUrl, subName, h('button', { class: 'btn primary', text: 'Add', on: { click: () => void addSubscription() } }));
        subUrl.onkeydown = (e) => e.key === 'Enter' && void addSubscription();
      } else if (addMode === 'paste') {
        panel = h(
          'div',
          { class: 'stack' },
          pasteArea,
          h('div', { class: 'row' }, h('button', { class: 'btn primary', text: 'Import', on: { click: () => void importText(pasteArea.value, 'paste') } }), h('button', { class: 'btn', text: 'From a file…', on: { click: () => textFile.click() } })),
        );
      } else {
        const drop = h('div', { class: 'drop' }, h('p', { text: 'Drop an image with a QR code here, paste one (Ctrl+V), or ' }), h('button', { class: 'link', text: 'choose an image…', on: { click: () => qrFile.click() } }));
        drop.ondragover = (e) => {
          e.preventDefault();
          drop.classList.add('over');
        };
        drop.ondragleave = () => drop.classList.remove('over');
        drop.ondrop = (e) => {
          e.preventDefault();
          drop.classList.remove('over');
          const f = e.dataTransfer?.files?.[0];
          if (f) void importQr(f);
        };
        panel = drop;
      }
      clear(
        addCard,
        h('div', { class: 'card-title' }, h('h2', { text: 'Add servers' }), seg),
        panel,
        h('p', { class: 'muted small', text: 'VLESS, VMess, Trojan and Shadowsocks, with REALITY, TLS, WebSocket, gRPC, HTTPUpgrade and XHTTP. Credentials are stored encrypted on this computer.' }),
        addResult ? h('p', { class: 'small', style: { color: addError ? 'var(--danger)' : 'var(--ok)' }, text: addResult }) : null,
        qrFile,
        textFile,
      );
    }

    // ---------------------------------------------------------------- subscriptions
    function subCard(s: SubscriptionSummary): HTMLElement {
      const u = s.usage;
      const used = (u?.upload ?? 0) + (u?.download ?? 0);
      const share = u?.total ? Math.min(1, used / u.total) : null;
      const now = Date.now() / 1000;
      const expired = u?.expire ? u.expire <= now : false;
      const soon = u?.expire ? u.expire - now < 3 * 86400 : false;
      const update = h('button', { class: 'btn small', text: 'Update' });
      update.onclick = async () => {
        update.disabled = true;
        update.textContent = 'Updating…';
        const r = await request('updateSubscription', { id: s.id });
        if (r.ok) {
          const x = r.result as SubscriptionResult;
          toast(`${s.name}: ${x.added} added, ${x.updated} updated, ${x.removed} removed.`);
        } else toast(r.error.message, 'error');
        await refreshList();
      };
      const history = (s.history ?? []).slice().reverse();
      const details = h(
        'details',
        null,
        h('summary', { class: 'small muted', text: `History (${history.length})` }),
        h(
          'div',
          { class: 'list small' },
          ...history.slice(0, 10).map((c) =>
            h('div', { class: 'list-row' }, h('span', { class: 'grow', text: dateTime(c.at) }), c.error ? h('span', { class: 'chip fail', text: c.error }) : h('span', { class: 'muted', text: `+${c.added} ~${c.updated} −${c.removed}${c.rejected ? ` · ${c.rejected} rejected` : ''}` })),
          ),
        ),
      );
      const health = s.lastError ? ['fail', 'Failing'] : expired ? ['fail', 'Expired'] : share !== null && share >= 1 ? ['fail', 'Used up'] : soon || (share ?? 0) > 0.9 ? ['warn', expired ? 'Expired' : soon ? 'Expires soon' : 'Almost used up'] : ['ok', 'OK'];
      return h(
        'section',
        { class: 'card sub-card stack' },
        h('div', { class: 'card-title' }, h('h3', { class: 'ellipsis', text: s.name }), h('span', { class: `chip ${health[0]}`, text: health[1] })),
        h('div', { class: 'muted small', text: `${s.host} · ${plural(s.serverCount, 'server')} · updated ${ago(s.lastUpdated)}${s.updateIntervalHours ? ` · provider asks every ${s.updateIntervalHours} h` : ''}` }),
        s.lastError ? h('p', { class: 'small', style: { color: 'var(--danger)' }, text: s.lastError }) : null,
        u
          ? h(
              'div',
              { class: 'usage' },
              share !== null ? h('div', { class: `meter ${share > 0.9 ? 'fail' : share > 0.75 ? 'warn' : ''}` }, h('div', { style: { width: `${Math.round(share * 100)}%` } })) : null,
              h('div', { class: 'row small' }, h('span', { class: 'grow', text: u.total ? `${bytes(used)} of ${bytes(u.total)} used` : `${bytes(used)} used` }), u.expire ? h('span', { class: expired ? '' : 'muted', text: `${expired ? 'Expired' : 'Expires'} ${date(u.expire)}` }) : null),
            )
          : null,
        details,
        h(
          'div',
          { class: 'row' },
          update,
          h('span', { class: 'spacer' }),
          confirmButton('Remove', `Remove with its ${plural(s.serverCount, 'server')}?`, 'btn small danger', async () => {
            const r = await request('deleteSubscription', { id: s.id, deleteServers: true });
            if (!r.ok) toast(r.error.message, 'error');
            await refreshList();
          }),
        ),
      );
    }

    // ---------------------------------------------------------------- servers table
    function visibleServers(): ServerSummary[] {
      const q = search.trim().toLowerCase();
      let list = ctx.list.servers.filter((s) => {
        if (group === 'manual' && s.subscriptionId) return false;
        if (group === 'favorites' && !ctx.list.favorites?.includes(s.id)) return false;
        if (group.startsWith('sub:') && s.subscriptionId !== group.slice(4)) return false;
        if (!q) return true;
        const tag = ctx.list.tags?.[s.id] ?? '';
        return `${s.name} ${s.address} ${s.protocol} ${tag}`.toLowerCase().includes(q);
      });
      const score = (s: ServerSummary) => ctx.list.scores?.[s.id];
      const fav = new Set(ctx.list.favorites ?? []);
      if (sort === 'name') list = list.sort((a, b) => a.name.localeCompare(b.name));
      if (sort === 'latency') list = list.sort((a, b) => (score(a)?.latencyMs ?? 1e9) - (score(b)?.latencyMs ?? 1e9));
      if (sort === 'score') list = list.sort((a, b) => (score(b)?.score ?? -1) - (score(a)?.score ?? -1));
      return list.sort((a, b) => Number(fav.has(b.id)) - Number(fav.has(a.id)));
    }

    async function runTests(ids: string[], kind: 'tcp' | 'real') {
      if (!ids.length) return;
      ids.forEach((id) => testing.add(id));
      for (const id of ids) ctx.tests.delete(id);
      renderTable();
      toast(`Testing ${plural(ids.length, 'server')}…`);
      const r = await request('testServers', { ids, kind });
      ids.forEach((id) => testing.delete(id));
      if (r.ok) {
        const results = (r.result as { results: TestResult[] }).results;
        for (const t of results) ctx.tests.set(t.serverId, { ...t, kind, at: Date.now() });
        const ok = results.filter((t) => t.ok).length;
        toast(`${ok} of ${results.length} servers answered.`);
      } else toast(r.error.message, 'error');
      await refreshList();
    }

    async function speedTest(s: ServerSummary) {
      speeds.set(s.id, 'Measuring…');
      renderTable();
      try {
        const r = await call<{ bytesPerSecond: number }>('speedTest', { serverId: s.id });
        speeds.set(s.id, mbps(r.bytesPerSecond));
      } catch (e) {
        speeds.set(s.id, 'Failed');
        toast((e as Error).message, 'error');
      }
      renderTable();
    }

    function resultCell(s: ServerSummary): HTMLElement {
      if (testing.has(s.id)) return h('span', { class: 'muted small', text: 'Testing…' });
      const t = ctx.tests.get(s.id);
      const sc = ctx.list.scores?.[s.id];
      const speed = speeds.get(s.id);
      const parts: HTMLElement[] = [];
      if (t) parts.push(t.ok ? h('span', { class: 'chip ok', text: `${t.ms} ms` }) : h('span', { class: 'chip fail', title: t.error, text: 'Failed' }));
      else if (sc?.latencyMs) parts.push(h('span', { class: 'chip', text: `${sc.latencyMs} ms` }));
      if (speed) parts.push(h('span', { class: 'chip', text: speed }));
      return h('div', { class: 'row' }, ...parts);
    }

    function renderTable() {
      const active = activeServerId();
      const shown = visibleServers();
      if (ctx.list.servers.length === 0) {
        clear(tableWrap, h('div', { class: 'empty', text: 'No servers yet. Add a subscription, paste links or scan a QR code above.' }));
        return;
      }
      const table = h('table', { class: 'table servers' });
      table.append(h('tr', null, h('th', { text: '' }), h('th', { text: 'Server' }), h('th', { text: 'Score' }), h('th', { text: 'Last test' }), h('th', { text: '' })));
      for (const s of shown) {
        const fav = ctx.list.favorites?.includes(s.id) ?? false;
        const sc = ctx.list.scores?.[s.id];
        const tag = ctx.list.tags?.[s.id];
        const sub = s.subscriptionId ? ctx.list.subscriptions.find((x) => x.id === s.subscriptionId)?.name : null;
        const favBtn = h('button', { class: 'fav', title: fav ? 'Remove from favorites' : 'Add to favorites', attrs: { 'aria-pressed': String(fav) } }, icon('star', 16));
        favBtn.onclick = async () => {
          await request('setFavorite', { id: s.id, favorite: !fav });
          await refreshList();
        };
        const isActive = s.id === active;
        const connectBtn = h('button', { class: `btn small ${isActive ? '' : 'primary'}`, text: isActive ? 'Connected' : 'Connect', disabled: isActive || !isReady() });
        connectBtn.onclick = async () => {
          const r = await request('connect', { serverId: s.id });
          if (!r.ok) toast(r.error.message, 'error');
        };
        const more = h('select', { class: 'small', ariaLabel: 'More actions', style: { width: '34px', padding: '0 4px' } });
        more.append(h('option', { value: '', text: '⋯' }), h('option', { value: 'real', text: 'Real test' }), h('option', { value: 'tcp', text: 'TCP ping' }), h('option', { value: 'speed', text: 'Speed test' }));
        if (isConnected() && !isActive) more.append(h('option', { value: 'compare', text: 'Compare with live route' }));
        more.append(h('option', { value: 'rename', text: 'Rename' }), h('option', { value: 'tag', text: tag ? 'Change tag' : 'Add tag' }), h('option', { value: 'delete', text: 'Remove' }));
        more.onchange = async () => {
          const a = more.value;
          more.value = '';
          if (a === 'real' || a === 'tcp') await runTests([s.id], a);
          if (a === 'speed') await speedTest(s);
          if (a === 'compare') compareDialog(s);
          if (a === 'rename') prompt('Rename server', 'Name', s.name, async (v) => {
            await call('renameServer', { id: s.id, name: v });
            await refreshList();
          });
          if (a === 'tag') prompt('Tag', 'A short label (empty removes it)', tag ?? '', async (v) => {
            await call('setTag', { id: s.id, tag: v });
            await refreshList();
          });
          if (a === 'delete') {
            if (!confirm(`Remove ${s.name}?`)) return;
            const r = await request('deleteServer', { id: s.id });
            if (!r.ok) toast(r.error.message, 'error');
            await refreshList();
          }
        };
        table.append(
          h(
            'tr',
            { class: isActive ? 'active' : '' },
            h('td', { style: { width: '28px' } }, favBtn),
            h(
              'td',
              { class: 'name' },
              h('div', { class: 'row' }, h('span', { class: 'title ellipsis', text: s.name }), tag ? h('span', { class: 'chip', text: tag }) : null),
              h('div', { class: 'sub ellipsis', text: `${protocolLine(s)} · ${s.address}:${s.port}${sub ? ` · ${sub}` : ''}` }),
            ),
            h('td', null, sc && sc.score !== null ? h('span', { class: `chip ${sc.label}`, title: sc.stale ? 'Measured a while ago' : '', text: `${sc.score}` }) : h('span', { class: 'muted small', text: '—' })),
            h('td', null, resultCell(s)),
            h('td', null, h('div', { class: 'actions' }, connectBtn, more)),
          ),
        );
      }
      clear(tableWrap, shown.length ? table : h('div', { class: 'empty', text: 'No servers match.' }));
    }

    function renderServers() {
      const searchBox = h('input', { type: 'search', placeholder: 'Search servers', value: search, style: { width: '220px' } });
      searchBox.oninput = () => {
        search = searchBox.value;
        renderTable();
      };
      const groupSel = h('select', { ariaLabel: 'Group' });
      groupSel.append(h('option', { value: 'all', text: `All (${ctx.list.servers.length})` }));
      if (ctx.list.favorites?.length) groupSel.append(h('option', { value: 'favorites', text: `Favorites (${ctx.list.favorites.length})` }));
      const manual = ctx.list.servers.filter((s) => !s.subscriptionId).length;
      if (manual) groupSel.append(h('option', { value: 'manual', text: `Added by hand (${manual})` }));
      for (const s of ctx.list.subscriptions) groupSel.append(h('option', { value: `sub:${s.id}`, text: `${s.name} (${s.serverCount})` }));
      groupSel.value = [...groupSel.options].some((o) => o.value === group) ? group : 'all';
      groupSel.onchange = () => {
        group = groupSel.value;
        renderTable();
      };
      const sortSel = h('select', { ariaLabel: 'Sort' });
      for (const [v, t] of [
        ['list', 'Original order'],
        ['score', 'Best score'],
        ['latency', 'Lowest latency'],
        ['name', 'Name'],
      ]) sortSel.append(h('option', { value: v, text: t }));
      sortSel.value = sort;
      sortSel.onchange = () => {
        sort = sortSel.value as Sort;
        renderTable();
      };
      clear(
        serversCard,
        h(
          'div',
          { class: 'toolbar' },
          h('h2', { class: 'grow', text: 'Servers' }),
          searchBox,
          groupSel,
          sortSel,
          h('button', { class: 'btn', text: 'TCP ping', title: 'How fast each server port answers', disabled: !isReady(), on: { click: () => void runTests(visibleServers().map((s) => s.id), 'tcp') } }),
          h('button', { class: 'btn', text: 'Real test', title: 'Open a page through each server (separately from your connection)', disabled: !isReady(), on: { click: () => void runTests(visibleServers().map((s) => s.id).slice(0, 200), 'real') } }),
        ),
        tableWrap,
      );
      renderTable();
    }

    function update() {
      renderAdd();
      clear(subsWrap, ...ctx.list.subscriptions.map(subCard));
      subsWrap.hidden = ctx.list.subscriptions.length === 0;
      renderServers();
    }

    const off = onEvent((e) => {
      if (e.event === 'progress') {
        testing.delete(e.progress.value.serverId);
        renderTable();
      }
    });
    update();
    return {
      update() {
        clear(subsWrap, ...ctx.list.subscriptions.map(subCard));
        subsWrap.hidden = ctx.list.subscriptions.length === 0;
        // Keep the search box focused while typing: rebuild only the table if the toolbar exists.
        if (serversCard.isConnected && serversCard.querySelector('input[type=search]') === document.activeElement) renderTable();
        else renderServers();
      },
      unmount() {
        off();
        document.removeEventListener('paste', onPaste);
      },
    };
  },
};
