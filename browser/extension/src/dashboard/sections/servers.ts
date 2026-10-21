import type { ImportResult, QualityScore, ServerSummary, SiteTiming, SubscriptionResult, SubscriptionSummary, TestResult } from '../../../../protocol/types';
import { call, request, tryServer } from '../../shared/client';
import { actionButton, confirmDialog, dialog, h, menuButton, promptDialog, region, select, toast, type MenuItem } from '../../shared/dom';
import { ago, bytes, date, dateTime, mbps, plural } from '../../shared/format';
import { icon } from '../../shared/icons';
import { protocolLine, subscriptionHealth, tagsInUse } from '../../shared/view';
import { decodeQrFromBlob, decodeQrFromImageData, looksLikeProxyConfig } from '../../popup/qr';
import { activeServerId, ctx, isConnected, isReady, onEvent, refreshList, type TestKind } from '../context';
import { qualityChip, qualityDetails } from '../quality';
import type { Section } from '../section';

type Sort = 'list' | 'score' | 'latency' | 'speed' | 'name';

function summarizeImport(r: ImportResult): string {
  const parts = [`${r.added} added, ${r.updated} updated`];
  if (r.rejected) parts.push(`${r.rejected} rejected${r.errors[0] ? ` (${r.errors[0].message})` : ''}`);
  if (r.unsupported) parts.push(`${r.unsupported} unsupported skipped`);
  if (r.warnings[0]) parts.push(r.warnings[0]);
  return parts.join(' · ');
}

// ------------------------------------------------------------------ tests

async function runTests(ids: string[], kind: TestKind, quiet = false) {
  if (!ids.length) return;
  for (const id of ids) {
    ctx.running.set(id, kind);
    ctx.tests.delete(id);
  }
  ctx.rerender();
  if (!quiet) toast(`Testing ${plural(ids.length, 'server')}…`);
  const r = await request('testServers', { ids, kind });
  for (const id of ids) ctx.running.delete(id);
  if (r.ok) {
    const results = (r.result as { results: TestResult[] }).results;
    for (const t of results) ctx.tests.set(t.serverId, { ...t, kind, at: Date.now() });
    if (!quiet) toast(`${results.filter((t) => t.ok).length} of ${results.length} servers answered.`);
    if (kind === 'real' && (await autoSpeedEnabled())) {
      const best = results
        .filter((t) => t.ok && !ctx.speeds.has(t.serverId))
        .sort((a, b) => (a.ms ?? 1e9) - (b.ms ?? 1e9))
        .slice(0, 5);
      for (const t of best) await speedTest(t.serverId, true);
    }
  } else toast(r.error.message, 'error');
  await refreshList();
}

async function autoSpeedEnabled(): Promise<boolean> {
  try {
    return (await chrome.storage.local.get('autoSpeed')).autoSpeed !== false;
  } catch {
    return true;
  }
}

async function speedTest(id: string, quiet = false) {
  ctx.speeds.set(id, 'measuring');
  ctx.rerender();
  try {
    const r = await call<{ bytesPerSecond: number }>('speedTest', { serverId: id });
    ctx.speeds.set(id, { bps: r.bytesPerSecond, at: Date.now() });
  } catch (e) {
    ctx.speeds.set(id, { bps: null, error: (e as Error).message, at: Date.now() });
    if (!quiet) toast((e as Error).message, 'error');
  }
  ctx.rerender();
}

// ------------------------------------------------------------------ dialogs

function tagDialog(s: ServerSummary) {
  const tag = ctx.list.tags?.[s.id] ?? '';
  promptDialog(
    tag ? 'Change tag' : 'Add a tag',
    'Tag (leave empty to remove it)',
    tag,
    async (v) => {
      await call('setTag', { id: s.id, tag: v });
      await refreshList();
    },
    { suggestions: tagsInUse(ctx.list).filter((t) => t !== tag), placeholder: 'Work, Fast, Streaming…', allowEmpty: true },
  );
}

async function detailsDialog(s: ServerSummary) {
  const body = h('div', { class: 'stack' }, h('p', { class: 'muted small', text: 'Loading the score…' }));
  const sub = s.subscriptionId ? ctx.list.subscriptions.find((x) => x.id === s.subscriptionId)?.name : null;
  dialog(s.name, [
    h(
      'dl',
      { class: 'kv' },
      h('dt', { text: 'Protocol' }),
      h('dd', { text: protocolLine(s) }),
      h('dt', { text: 'Address' }),
      h('dd', { class: 'mono', text: `${s.address}:${s.port}` }),
      h('dt', { text: 'Source' }),
      h('dd', { text: sub ? `Subscription “${sub}”` : 'Added by hand' }),
      ctx.list.tags?.[s.id] ? h('dt', { text: 'Tag' }) : null,
      ctx.list.tags?.[s.id] ? h('dd', { text: ctx.list.tags[s.id] }) : null,
    ),
    h('h3', { text: 'Network quality' }),
    body,
  ], [h('button', { class: 'btn', text: 'Close', on: { click: (e) => (e.target as HTMLElement).closest('dialog')?.close() } })], { wide: true });
  const r = await request('getQuality', { id: s.id });
  body.replaceChildren(r.ok ? qualityDetails(r.result as QualityScore) : h('p', { class: 'small error-text', text: r.error.message }));
}

function compareDialog(s: ServerSummary) {
  const urls = h('textarea', { attrs: { rows: '4' }, style: { width: '100%' }, placeholder: 'https://www.youtube.com\nhttps://www.wikipedia.org' });
  urls.value = 'https://www.google.com\nhttps://www.youtube.com\nhttps://www.wikipedia.org';
  const out = h('div', { class: 'stack' });
  const run = h('button', { class: 'btn primary', text: 'Compare' });
  const d = dialog(
    `Compare ${s.name} with the live route`,
    [
      h('p', { class: 'muted small', text: 'The same sites open through your current connection and through this server, side by side. Your connection does not change.' }),
      h('label', { class: 'field' }, h('span', { text: 'Sites (https only, up to 8)' }), urls),
      out,
    ],
    [h('button', { class: 'btn', text: 'Close', on: { click: () => d.close() } }), run],
    { wide: true },
  );
  run.onclick = async () => {
    run.disabled = true;
    out.replaceChildren(h('p', { class: 'muted small', text: 'Opening the sites through both routes…' }));
    try {
      const list = urls.value.split(/\s+/).filter(Boolean).slice(0, 8);
      const r = await call<{ live: SiteTiming[]; candidate: SiteTiming[] }>('compare', { candidateId: s.id, urls: list });
      const cell = (t: SiteTiming | undefined) => h('td', null, !t ? '—' : t.ok ? h('span', { class: 'chip ok', text: `${t.ms} ms` }) : h('span', { class: 'chip fail', title: t.error ?? '', text: t.error ? 'Failed' : `HTTP ${t.status}` }));
      const host = (u: string) => {
        try {
          return new URL(u).hostname;
        } catch {
          return u;
        }
      };
      const table = h('table', { class: 'table' }, h('thead', null, h('tr', null, h('th', { text: 'Site' }), h('th', { text: 'Live route' }), h('th', { text: s.name }))));
      const tbody = h('tbody');
      let faster = 0;
      let liveOk = 0;
      let candOk = 0;
      r.live.forEach((t, i) => {
        const c = r.candidate[i];
        if (t.ok) liveOk++;
        if (c?.ok) candOk++;
        if (c?.ok && (!t.ok || (c.ms ?? 1e9) < (t.ms ?? 1e9))) faster++;
        tbody.append(h('tr', null, h('td', { class: 'ellipsis', text: host(t.url) }), cell(t), cell(c)));
      });
      table.append(tbody);
      const better = candOk > liveOk || (candOk === liveOk && faster > r.live.length / 2);
      out.replaceChildren(
        table,
        h('div', { class: `banner ${better ? 'info' : ''}` }, h('div', { class: 'grow small', text: `${s.name} opened ${candOk} of ${r.live.length} sites (the live route ${liveOk}) and was faster on ${faster}. ${better ? 'It looks better.' : 'The live route looks as good or better.'}` })),
        h(
          'div',
          { class: 'row', style: { justifyContent: 'flex-end' } },
          actionButton('Try it for 30 min', 'btn', async () => {
            const t = await tryServer(s.id, 30);
            if (!t.ok) throw new Error(t.error.message);
            toast(`Trying ${s.name} for 30 min.`);
            d.close();
          }),
          actionButton('Switch to it', 'btn primary', async () => {
            await call('connect', { serverId: s.id });
            d.close();
          }),
        ),
      );
    } catch (e) {
      out.replaceChildren(h('p', { class: 'small error-text', text: (e as Error).message }));
    }
    run.disabled = false;
  };
}

/** Camera QR scanning (the camera is on only while this dialog is open). */
function cameraDialog(onText: (text: string) => void) {
  const video = h('video', { attrs: { playsinline: '', muted: '' }, class: 'camera' });
  const status = h('p', { class: 'muted small', text: 'Starting the camera…' });
  let stream: MediaStream | null = null;
  let timer: ReturnType<typeof setInterval> | null = null;
  const d = dialog('Scan with the camera', [video, status], [h('button', { class: 'btn', text: 'Cancel', on: { click: () => d.close() } })]);
  d.addEventListener('close', () => {
    if (timer) clearInterval(timer);
    stream?.getTracks().forEach((t) => t.stop());
  });
  navigator.mediaDevices
    .getUserMedia({ video: { facingMode: 'environment' }, audio: false })
    .then(async (s) => {
      stream = s;
      if (!d.open) return s.getTracks().forEach((t) => t.stop());
      video.srcObject = s;
      await video.play();
      status.textContent = 'Hold the QR code in front of the camera.';
      const canvas = document.createElement('canvas');
      const g = canvas.getContext('2d', { willReadFrequently: true });
      timer = setInterval(() => {
        if (!g || !video.videoWidth) return;
        canvas.width = video.videoWidth;
        canvas.height = video.videoHeight;
        g.drawImage(video, 0, 0);
        const text = decodeQrFromImageData(g.getImageData(0, 0, canvas.width, canvas.height).data, canvas.width, canvas.height);
        if (text) {
          d.close();
          onText(text);
        }
      }, 300);
    })
    .catch((e: Error) => {
      status.textContent = e.name === 'NotAllowedError' ? 'Camera access was not allowed.' : `The camera could not be started: ${e.message}`;
      status.className = 'small error-text';
    });
}

// ------------------------------------------------------------------ section

export const serversSection: Section = {
  id: 'servers',
  title: 'Servers',
  subtitle: 'Subscriptions, links and QR codes. Test servers and keep your favorites on top.',
  icon: 'servers',
  mount(el) {
    let addMode: 'subscription' | 'paste' | 'qr' = 'subscription';
    let search = '';
    let group = 'all';
    let sort: Sort = 'list';
    let addResult = '';
    let addError = false;

    // ---------------------------------------------------------------- add servers
    const pasteArea = h('textarea', { attrs: { rows: '4' }, style: { width: '100%' }, placeholder: 'vless://…  vmess://…  trojan://…  ss://…  or Xray JSON' });
    const subName = h('input', { type: 'text', placeholder: 'Name (optional)', style: { width: '200px' } });
    const subUrl = h('input', { type: 'url', placeholder: 'https://…', class: 'grow' });
    const qrFile = h('input', { type: 'file', attrs: { accept: 'image/*' }, hidden: true });
    const textFile = h('input', { type: 'file', attrs: { accept: '.txt,.json,text/plain,application/json' }, hidden: true });

    function setResult(text: string, error: boolean) {
      addResult = text;
      addError = error;
      addCard.render(true);
    }

    async function importText(text: string, source: 'paste' | 'qr' | 'file') {
      if (!text.trim()) return setResult('Nothing to import.', true);
      setResult('Importing…', false);
      const r = await request('importText', { text, source });
      if (r.ok) {
        const x = r.result as ImportResult;
        setResult(summarizeImport(x), x.added + x.updated === 0);
        if (x.added + x.updated) pasteArea.value = '';
        await refreshList();
      } else setResult(r.error.message, true);
    }

    async function importQrText(text: string | null) {
      if (!text) return setResult('No QR code found in the image.', true);
      if (!looksLikeProxyConfig(text)) return setResult('This QR code does not contain a server configuration.', true);
      await importText(text, 'qr');
    }

    async function importQr(blob: Blob) {
      try {
        setResult('Reading the QR code…', false);
        await importQrText(await decodeQrFromBlob(blob));
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
    subUrl.onkeydown = (e) => e.key === 'Enter' && void addSubscription();
    const onPaste = (e: ClipboardEvent) => {
      if (addMode !== 'qr') return;
      const blob = [...(e.clipboardData?.items ?? [])].find((i) => i.type.startsWith('image/'))?.getAsFile();
      if (blob) {
        e.preventDefault();
        void importQr(blob);
      }
    };
    document.addEventListener('paste', onPaste);

    const addCard = region(h('section', { class: 'card stack' }), () => {
      const seg = h('div', { class: 'segmented', role: 'group' });
      for (const [m, label] of [
        ['subscription', 'Subscription'],
        ['paste', 'Links or JSON'],
        ['qr', 'QR code'],
      ] as const) {
        seg.append(h('button', { type: 'button', text: label, attrs: { 'aria-pressed': String(addMode === m) }, on: { click: () => ((addMode = m), (addResult = ''), addCard.render(true)) } }));
      }
      let panel: HTMLElement;
      if (addMode === 'subscription') {
        panel = h('div', { class: 'row wrap' }, subUrl, subName, h('button', { class: 'btn primary', text: 'Add', disabled: !isReady(), on: { click: () => void addSubscription() } }));
      } else if (addMode === 'paste') {
        panel = h(
          'div',
          { class: 'stack' },
          pasteArea,
          h('div', { class: 'row' }, h('button', { class: 'btn primary', text: 'Import', disabled: !isReady(), on: { click: () => void importText(pasteArea.value, 'paste') } }), h('button', { class: 'btn', text: 'From a file…', on: { click: () => textFile.click() } })),
        );
      } else {
        const drop = h(
          'div',
          { class: 'drop' },
          icon('qr', 28),
          h('p', { text: 'Drop an image with a QR code here or paste one (Ctrl+V).' }),
          h('div', { class: 'row', style: { justifyContent: 'center' } }, h('button', { class: 'btn', text: 'Choose an image…', on: { click: () => qrFile.click() } }), h('button', { class: 'btn', text: 'Use the camera', on: { click: () => cameraDialog((t) => void importQrText(t)) } })),
          h('p', { class: 'small', text: 'Tip: the Scan QR button in the toolbar popup reads a QR code shown on the current page.' }),
        );
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
      return [
        h('div', { class: 'card-title' }, h('h2', { text: 'Add servers' }), seg),
        panel,
        h('p', { class: 'muted small', text: 'VLESS, VMess, Trojan and Shadowsocks, with REALITY, TLS, WebSocket, gRPC, HTTPUpgrade and XHTTP. Credentials are stored encrypted on this computer.' }),
        addResult ? h('p', { class: `small ${addError ? 'error-text' : 'ok-text'}`, text: addResult }) : null,
        qrFile,
        textFile,
      ];
    }, () => [addMode, addResult, addError, isReady()]);

    // ---------------------------------------------------------------- subscriptions
    function subCard(s: SubscriptionSummary): HTMLElement {
      const u = s.usage;
      const used = (u?.upload ?? 0) + (u?.download ?? 0);
      const share = u?.total ? Math.min(1, used / u.total) : null;
      const now = Date.now() / 1000;
      const health = subscriptionHealth(s, now);
      const history = (s.history ?? []).slice().reverse();
      return h(
        'section',
        { class: 'card sub-card stack' },
        h('div', { class: 'card-title' }, h('h3', { class: 'ellipsis', title: s.name, text: s.name }), h('span', { class: `chip ${health.tone}`, title: health.detail, text: health.label })),
        h('div', { class: 'muted small', text: `${s.host} · ${plural(s.serverCount, 'server')} · updated ${ago(s.lastUpdated)}${s.updateIntervalHours ? ` · provider asks every ${s.updateIntervalHours} h` : ''}` }),
        health.tone !== 'ok' ? h('p', { class: `small ${health.tone === 'fail' ? 'error-text' : 'warn-text'}`, text: health.detail }) : null,
        u
          ? h(
              'div',
              { class: 'usage' },
              share !== null ? h('div', { class: `meter ${share > 0.9 ? 'fail' : share > 0.75 ? 'warn' : ''}` }, h('div', { style: { width: `${Math.round(share * 100)}%` } })) : null,
              h(
                'div',
                { class: 'row small' },
                h('span', { class: 'grow', text: u.total ? `${bytes(used)} of ${bytes(u.total)} used` : `${bytes(used)} used` }),
                u.expire ? h('span', { class: u.expire <= now ? 'error-text' : 'muted', text: `${u.expire <= now ? 'Expired' : 'Expires'} ${date(u.expire)}` }) : null,
              ),
            )
          : null,
        history.length
          ? h(
              'details',
              { data: { key: `history-${s.id}` } },
              h('summary', { class: 'small muted', text: `Update history (${history.length})` }),
              h(
                'div',
                { class: 'list small' },
                ...history.slice(0, 10).map((c) =>
                  h(
                    'div',
                    { class: 'list-row' },
                    h('span', { class: 'grow', text: dateTime(c.at) }),
                    c.error ? h('span', { class: 'chip fail', title: c.error, text: 'Failed' }) : h('span', { class: 'muted', text: `+${c.added} · ${c.updated} changed · −${c.removed}${c.rejected ? ` · ${c.rejected} rejected` : ''}` }),
                  ),
                ),
              ),
            )
          : null,
        h(
          'div',
          { class: 'row' },
          actionButton('Update', 'btn small', async () => {
            const x = await call<SubscriptionResult>('updateSubscription', { id: s.id });
            toast(`${s.name}: ${x.added} added, ${x.updated} updated, ${x.removed} removed.`);
            await refreshList();
          }, 'Updating…', { disabled: !isReady() }),
          h('button', { class: 'btn small', text: 'Show servers', on: { click: () => ((group = `sub:${s.id}`), renderServers(true)) } }),
          h('span', { class: 'spacer' }),
          menuButton(() => [
            {
              label: 'Remove with its servers',
              danger: true,
              run: async () => {
                if (!(await confirmDialog(`Remove ${s.name}?`, `The subscription and its ${plural(s.serverCount, 'server')} are removed from this computer.`, 'Remove', true))) return;
                await call('deleteSubscription', { id: s.id, deleteServers: true });
                await refreshList();
              },
            },
            {
              label: 'Remove, keep the servers',
              run: async () => {
                await call('deleteSubscription', { id: s.id, deleteServers: false });
                await refreshList();
              },
            },
          ]),
        ),
      );
    }

    const subsWrap = region(h('div', { class: 'grid2' }), () => ctx.list.subscriptions.map(subCard), () => [ctx.list.subscriptions, isReady(), Math.floor(Date.now() / 60000)]);

    // ---------------------------------------------------------------- servers table
    const scoreOf = (s: ServerSummary) => ctx.list.scores?.[s.id];
    const speedOf = (s: ServerSummary) => {
      const x = ctx.speeds.get(s.id);
      return x && x !== 'measuring' ? (x.bps ?? -1) : -1;
    };

    function visibleServers(): ServerSummary[] {
      const q = search.trim().toLowerCase();
      const fav = new Set(ctx.list.favorites ?? []);
      let list = ctx.list.servers.filter((s) => {
        const tag = ctx.list.tags?.[s.id] ?? '';
        if (group === 'manual' && s.subscriptionId) return false;
        if (group === 'favorites' && !fav.has(s.id)) return false;
        if (group.startsWith('sub:') && s.subscriptionId !== group.slice(4)) return false;
        if (group.startsWith('tag:') && tag !== group.slice(4)) return false;
        if (group === 'failed' && ctx.tests.get(s.id)?.ok !== false) return false;
        if (!q) return true;
        return `${s.name} ${s.address} ${s.protocol} ${s.transportLabel} ${tag}`.toLowerCase().includes(q);
      });
      if (sort === 'name') list = list.sort((a, b) => a.name.localeCompare(b.name));
      if (sort === 'latency') list = list.sort((a, b) => (ctx.tests.get(a.id)?.ms ?? scoreOf(a)?.latencyMs ?? 1e9) - (ctx.tests.get(b.id)?.ms ?? scoreOf(b)?.latencyMs ?? 1e9));
      if (sort === 'score') list = list.sort((a, b) => (scoreOf(b)?.score ?? -1) - (scoreOf(a)?.score ?? -1));
      if (sort === 'speed') list = list.sort((a, b) => speedOf(b) - speedOf(a));
      return list.sort((a, b) => Number(fav.has(b.id)) - Number(fav.has(a.id)));
    }

    function resultCell(s: ServerSummary): HTMLElement {
      const running = ctx.running.get(s.id);
      if (running) return h('span', { class: 'muted small', text: running === 'tcp' ? 'Pinging…' : 'Testing…' });
      const t = ctx.tests.get(s.id);
      const sc = scoreOf(s);
      const sp = ctx.speeds.get(s.id);
      const parts: HTMLElement[] = [];
      if (t) parts.push(t.ok ? h('span', { class: 'chip ok', title: t.kind === 'tcp' ? 'TCP ping' : 'Real test through the tunnel', text: `${t.ms} ms` }) : h('span', { class: 'chip fail', title: t.error ?? '', text: 'Failed' }));
      else if (sc?.latencyMs) parts.push(h('span', { class: 'chip', title: `Measured ${ago(sc.measuredAt / (sc.measuredAt > 1e12 ? 1000 : 1))}`, text: `${sc.latencyMs} ms` }));
      if (sp === 'measuring') parts.push(h('span', { class: 'muted small', text: 'Speed…' }));
      else if (sp) parts.push(sp.bps !== null ? h('span', { class: 'chip', title: 'Download speed', text: mbps(sp.bps) }) : h('span', { class: 'chip fail', title: sp.error ?? '', text: 'Speed failed' }));
      return parts.length ? h('div', { class: 'row wrap', style: { gap: '4px' } }, ...parts) : h('span', { class: 'muted small', text: '—' });
    }

    function rowMenu(s: ServerSummary, isActive: boolean): MenuItem[] {
      const tag = ctx.list.tags?.[s.id];
      return [
        { label: 'Details and score', run: () => void detailsDialog(s) },
        { label: 'Real test', separated: true, disabled: !isReady(), run: () => runTests([s.id], 'real') },
        { label: 'TCP ping', disabled: !isReady(), run: () => runTests([s.id], 'tcp') },
        { label: 'Speed test', disabled: !isReady(), run: () => speedTest(s.id) },
        { label: 'Compare with the live route', disabled: !isConnected() || isActive, run: () => compareDialog(s) },
        {
          label: 'Try it for 30 minutes',
          disabled: !isReady() || isActive,
          run: async () => {
            const r = await tryServer(s.id, 30);
            if (!r.ok) throw new Error(r.error.message);
            toast(`Trying ${s.name} for 30 min.`);
          },
        },
        { label: 'Rename', separated: true, run: () => promptDialog('Rename server', 'Name', s.name, async (v) => void (await call('renameServer', { id: s.id, name: v }), await refreshList())) },
        { label: tag ? 'Change tag' : 'Add a tag', run: () => tagDialog(s) },
        {
          label: 'Copy address',
          run: async () => {
            await navigator.clipboard.writeText(`${s.address}:${s.port}`);
            toast('Address copied.');
          },
        },
        {
          label: 'Remove',
          danger: true,
          separated: true,
          run: async () => {
            if (!(await confirmDialog(`Remove ${s.name}?`, s.subscriptionId ? 'It comes back with the next subscription update.' : 'Its link and credentials are deleted from this computer.', 'Remove', true))) return;
            await call('deleteServer', { id: s.id });
            await refreshList();
          },
        },
      ];
    }

    const table = region(h('div', { class: 'table-wrap', data: { scroll: 'servers' } }), () => {
      if (ctx.list.servers.length === 0) return [h('div', { class: 'empty' }, icon('servers', 28), h('p', { text: 'No servers yet. Add a subscription, paste links or scan a QR code above.' }))];
      const shown = visibleServers();
      if (!shown.length) return [h('div', { class: 'empty', text: 'No servers match.' })];
      const active = activeServerId();
      const tbody = h('tbody');
      for (const s of shown) {
        const fav = ctx.list.favorites?.includes(s.id) ?? false;
        const sc = scoreOf(s);
        const tag = ctx.list.tags?.[s.id];
        const sub = s.subscriptionId ? ctx.list.subscriptions.find((x) => x.id === s.subscriptionId)?.name : null;
        const isActive = s.id === active;
        const favBtn = h('button', { class: 'fav', type: 'button', title: fav ? 'Remove from favorites' : 'Add to favorites', ariaLabel: fav ? 'Remove from favorites' : 'Add to favorites', attrs: { 'aria-pressed': String(fav) } }, icon('star', 16));
        favBtn.onclick = async () => {
          await request('setFavorite', { id: s.id, favorite: !fav });
          await refreshList();
        };
        const connectBtn = isActive
          ? h('span', { class: ctx.app?.status?.state === 'connecting' ? 'chip warn' : 'chip ok', text: ctx.app?.status?.state === 'connecting' ? 'Connecting' : 'Connected' })
          : actionButton('Connect', 'btn small primary', async () => void (await call('connect', { serverId: s.id })), undefined, { disabled: !isReady() });
        tbody.append(
          h(
            'tr',
            { class: isActive ? 'active' : '' },
            h('td', { class: 'fav-cell' }, favBtn),
            h(
              'td',
              { class: 'name' },
              h('div', { class: 'name-line' }, h('span', { class: 'title ellipsis', title: s.name, text: s.name }), tag ? h('button', { class: 'chip tag', type: 'button', title: 'Change the tag', text: tag, on: { click: () => tagDialog(s) } }) : null),
              h('div', { class: 'sub ellipsis', text: `${protocolLine(s)} · ${s.address}:${s.port}${sub ? ` · ${sub}` : ''}` }),
            ),
            h('td', null, sc ? h('button', { class: 'chip-btn plain', type: 'button', title: 'What the score is made of', on: { click: () => void detailsDialog(s) } }, qualityChip(sc.score, sc.label, sc.stale)) : h('span', { class: 'muted small', text: '—' })),
            h('td', null, resultCell(s)),
            h('td', null, h('div', { class: 'actions' }, connectBtn, menuButton(() => rowMenu(s, isActive)))),
          ),
        );
      }
      return [
        h(
          'table',
          { class: 'table servers' },
          h('thead', null, h('tr', null, h('th', { ariaLabel: 'Favorite' }), h('th', { text: `Server (${shown.length})` }), h('th', { text: 'Quality' }), h('th', { text: 'Last test' }), h('th'))),
          tbody,
        ),
      ];
    }, () => [ctx.list, [...ctx.tests], [...ctx.running], [...ctx.speeds], activeServerId(), ctx.app?.status?.state, isReady(), isConnected(), search, group, sort]);

    const searchBox = h('input', { type: 'search', placeholder: 'Search servers', ariaLabel: 'Search servers', style: { width: '200px' } });
    searchBox.oninput = () => {
      search = searchBox.value;
      table.render();
    };

    const toolbar = region(h('div', { class: 'toolbar' }), () => {
      const opts: [string, string][] = [['all', `All (${ctx.list.servers.length})`]];
      if (ctx.list.favorites?.length) opts.push(['favorites', `Favorites (${ctx.list.favorites.length})`]);
      const manual = ctx.list.servers.filter((s) => !s.subscriptionId).length;
      if (manual) opts.push(['manual', `Added by hand (${manual})`]);
      for (const s of ctx.list.subscriptions) opts.push([`sub:${s.id}`, `${s.name} (${s.serverCount})`]);
      for (const t of tagsInUse(ctx.list)) opts.push([`tag:${t}`, `Tag: ${t} (${Object.values(ctx.list.tags ?? {}).filter((x) => x === t).length})`]);
      const failed = ctx.list.servers.filter((s) => ctx.tests.get(s.id)?.ok === false).length;
      if (failed) opts.push(['failed', `Failed the last test (${failed})`]);
      if (!opts.some(([v]) => v === group)) group = 'all';
      const ids = () => visibleServers().map((s) => s.id);
      return [
        h('h2', { class: 'grow', text: 'Servers' }),
        searchBox,
        select(opts, group, (v) => ((group = v), table.render()), { ariaLabel: 'Show' }),
        select(
          [
            ['list', 'Original order'],
            ['score', 'Best quality'],
            ['latency', 'Lowest latency'],
            ['speed', 'Fastest download'],
            ['name', 'Name'],
          ],
          sort,
          (v) => ((sort = v as Sort), table.render()),
          { ariaLabel: 'Sort' },
        ),
        h(
          'div',
          { class: 'row', style: { gap: '4px' } },
          actionButton('Test', 'btn', () => runTests(ids().slice(0, 200), 'real'), 'Testing…', { disabled: !isReady() || !ctx.list.servers.length, title: 'Open a page through each shown server (separately from your connection)' }),
          menuButton(
            () => [
              { label: 'Real test of the shown servers', disabled: !isReady(), run: () => runTests(ids().slice(0, 200), 'real') },
              { label: 'TCP ping of the shown servers', disabled: !isReady(), run: () => runTests(ids().slice(0, 500), 'tcp') },
              {
                label: 'Speed test of the 5 best',
                disabled: !isReady(),
                run: async () => {
                  const best = visibleServers()
                    .filter((s) => ctx.tests.get(s.id)?.ok !== false)
                    .sort((a, b) => (scoreOf(b)?.score ?? -1) - (scoreOf(a)?.score ?? -1))
                    .slice(0, 5);
                  for (const s of best) await speedTest(s.id, true);
                },
              },
              {
                label: `Remove servers that failed the last test${failed ? ` (${failed})` : ''}`,
                danger: true,
                separated: true,
                disabled: !failed,
                run: async () => {
                  const bad = ctx.list.servers.filter((s) => ctx.tests.get(s.id)?.ok === false && s.id !== activeServerId());
                  if (!(await confirmDialog(`Remove ${plural(bad.length, 'server')}?`, 'Servers from a subscription come back with its next update.', 'Remove', true))) return;
                  for (const s of bad) await request('deleteServer', { id: s.id });
                  for (const s of bad) ctx.tests.delete(s.id);
                  toast(`${plural(bad.length, 'server')} removed.`);
                  await refreshList();
                },
              },
            ],
            { label: 'More tests' },
          ),
        ),
      ];
    }, () => [ctx.list.servers.length, ctx.list.favorites, ctx.list.subscriptions.map((s) => [s.id, s.name, s.serverCount]), ctx.list.tags, [...ctx.tests].map(([k, v]) => [k, v.ok]), isReady(), group, sort]);

    function renderServers(force = false) {
      toolbar.render(force);
      table.render(force);
    }

    el.append(addCard.el, subsWrap.el, h('section', { class: 'card stack' }, toolbar.el, table.el));

    const off = onEvent((e) => {
      if (e.event === 'progress') table.render();
    });
    function update() {
      addCard.render();
      subsWrap.render();
      subsWrap.el.hidden = ctx.list.subscriptions.length === 0;
      renderServers();
    }
    update();
    return {
      update,
      unmount() {
        off();
        document.removeEventListener('paste', onPaste);
      },
    };
  },
};
