import type { RoutingMode, RuleAction, RuleInput, RuleMatch, Scenario, SiteRule } from '../../../../protocol/types';
import { call, download, request } from '../../shared/client';
import { actionButton, confirmButton, confirmDialog, copyText, dialog, h, region, select, toast, toggle } from '../../shared/dom';
import { DURATIONS, durationLabel, plural, until } from '../../shared/format';
import { icon } from '../../shared/icons';
import { decodeRecipe, encodeRecipe } from '../../shared/recipe';
import { outcomeFor, ruleActive, shadowedRules, type Shadow } from '../../shared/rules-eval';
import { hostOfUrl } from '../../shared/site';
import { ctx, isReady, refreshRules, serverName, sortedServers } from '../context';
import type { Section } from '../section';

const ACTION_LABEL: Record<RuleAction, string> = { proxy: 'Tunnel', direct: 'Direct', block: 'Block' };
const MATCH_LABEL: Record<RuleMatch, string> = { domain: 'Sites', ip: 'IP addresses', port: 'Ports' };
const MATCH_HINT: Record<RuleMatch, string> = {
  domain: 'youtube.com, *.example.org, a full URL, full:api.example.com, keyword:ads, regexp:^cdn[0-9]+\\.',
  ip: '1.2.3.4, 10.0.0.0/8, 2001:db8::/32',
  port: '443, 6881-6889',
};
const nowSec = () => Math.floor(Date.now() / 1000);

/** The user's own rules as rule inputs (for recipes, profiles of rules and Undo). */
function ownRuleInputs(rules: SiteRule[], keepServers: boolean): RuleInput[] {
  const now = nowSec();
  return rules
    .filter((r) => !r.scenario && (r.expiresAt === null || r.expiresAt > now))
    .map((r) => ({
      name: r.name,
      match: r.match,
      text: r.values.join('\n'),
      action: r.action,
      serverId: keepServers && r.action === 'proxy' ? r.serverId : null,
      minutes: r.expiresAt ? Math.max(1, Math.ceil((r.expiresAt - now) / 60)) : null,
      enabled: r.enabled,
    }));
}

// ------------------------------------------------------------------ undo of the last recipe

interface Snapshot {
  at: number;
  mode: RoutingMode;
  rules: RuleInput[];
}

async function saveSnapshot() {
  if (!ctx.rules) return;
  const snap: Snapshot = { at: Date.now(), mode: ctx.rules.mode, rules: ownRuleInputs(ctx.rules.rules, true) };
  try {
    await chrome.storage.local.set({ recipeUndo: snap });
  } catch {
    /* Undo is a convenience */
  }
}

async function loadSnapshot(): Promise<Snapshot | null> {
  try {
    const s = (await chrome.storage.local.get('recipeUndo')).recipeUndo as Snapshot | undefined;
    return s && Array.isArray(s.rules) ? s : null;
  } catch {
    return null;
  }
}

// ------------------------------------------------------------------ dialogs

function ruleDialog(existing: SiteRule | null, prefill?: Partial<RuleInput>) {
  const name = h('input', { type: 'text', value: existing?.name ?? prefill?.name ?? '', placeholder: 'Optional' });
  const match = select(
    (['domain', 'ip', 'port'] as RuleMatch[]).map((m) => [m, MATCH_LABEL[m]]),
    existing?.match ?? prefill?.match ?? 'domain',
    () => renderHint(),
  );
  const text = h('textarea', { attrs: { rows: '5' }, style: { width: '100%' } });
  text.value = existing ? existing.values.join('\n') : (prefill?.text ?? '');
  const hint = h('div', { class: 'muted small' });
  const action = h('div', { class: 'segmented', role: 'group' });
  let chosen: RuleAction = existing?.action ?? prefill?.action ?? 'proxy';
  const server = select([['', 'Main server'], ...sortedServers().map((s) => [s.id, s.name] as [string, string])], existing?.serverId ?? '', () => undefined);
  const serverField = h('label', { class: 'field grow' }, h('span', { text: 'Server' }), server);
  // Editing a temporary rule keeps its end unless another duration is chosen.
  const remaining = existing?.expiresAt && existing.expiresAt > nowSec() ? Math.ceil((existing.expiresAt - nowSec()) / 60) : 0;
  const durOpts: [string, string][] = [['0', 'Always']];
  if (remaining) durOpts.push([`keep:${remaining}`, `Keep the current end (in ${until(existing!.expiresAt!)})`]);
  for (const m of DURATIONS) durOpts.push([String(m), `For ${durationLabel(m)}`]);
  const duration = select(durOpts, remaining ? `keep:${remaining}` : '0', () => undefined);
  const renderAction = () => {
    action.replaceChildren();
    for (const a of ['proxy', 'direct', 'block'] as RuleAction[]) {
      action.append(h('button', { type: 'button', text: ACTION_LABEL[a], attrs: { 'aria-pressed': String(chosen === a) }, on: { click: () => ((chosen = a), renderAction()) } }));
    }
    serverField.hidden = chosen !== 'proxy';
  };
  const renderHint = () => (hint.textContent = `One per line or separated by commas. Examples: ${MATCH_HINT[match.value as RuleMatch]}`);
  renderAction();
  renderHint();
  const save = h('button', { class: 'btn primary', text: existing ? 'Save' : 'Add rule' });
  const err = h('p', { class: 'small error-text', hidden: true });
  const d = dialog(
    existing ? 'Edit rule' : 'New rule',
    [
      h('div', { class: 'row' }, h('label', { class: 'field grow' }, h('span', { text: 'Name' }), name), h('label', { class: 'field' }, h('span', { text: 'Match' }), match)),
      h('label', { class: 'field' }, h('span', { text: 'Entries' }), text, hint),
      h('div', { class: 'field' }, h('span', { text: 'Then' }), action),
      h('div', { class: 'row wrap' }, serverField, h('label', { class: 'field grow' }, h('span', { text: 'Duration' }), duration)),
      err,
    ],
    [h('button', { class: 'btn', text: 'Cancel', on: { click: () => d.close() } }), save],
    { wide: true },
  );
  text.focus();
  save.onclick = async () => {
    const dv = duration.value;
    const minutes = dv.startsWith('keep:') ? Number(dv.slice(5)) : Number(dv);
    const rule: RuleInput = {
      name: name.value.trim(),
      match: match.value as RuleMatch,
      text: text.value,
      action: chosen,
      serverId: chosen === 'proxy' && server.value ? server.value : null,
      minutes: minutes || null,
      enabled: existing?.enabled ?? true,
    };
    save.disabled = true;
    const r = existing ? await request('updateRule', { id: existing.id, rule }) : await request('addRule', rule);
    save.disabled = false;
    if (!r.ok) {
      err.textContent = r.error.message;
      err.hidden = false;
      return;
    }
    d.close();
    await refreshRules();
  };
}

function recipeDialog(onApplied: () => void) {
  const rules = ctx.rules;
  const exportText = rules ? encodeRecipe('My rules', rules.mode, rules.rules.filter((r) => !r.scenario && r.expiresAt === null)) : '';
  const input = h('textarea', { attrs: { rows: '3' }, style: { width: '100%' }, placeholder: 'Paste a PerRoute recipe' });
  const file = h('input', { type: 'file', attrs: { accept: '.txt,.perroute-recipe,text/plain' }, hidden: true });
  const preview = h('div', { class: 'stack' });
  const apply = h('button', { class: 'btn primary', text: 'Replace my rules', disabled: true });
  const add = h('button', { class: 'btn', text: 'Add to my rules', disabled: true });
  let decoded: ReturnType<typeof decodeRecipe> | null = null;

  const showPreview = () => {
    try {
      decoded = input.value.trim() ? decodeRecipe(input.value) : null;
    } catch (e) {
      decoded = null;
      preview.replaceChildren(h('p', { class: 'small error-text', text: (e as Error).message }));
      apply.disabled = add.disabled = true;
      return;
    }
    apply.disabled = add.disabled = !decoded;
    if (!decoded) return preview.replaceChildren();
    const own = rules?.rules.filter((r) => !r.scenario) ?? [];
    const key = (m: string, v: string[], a: string) => `${m}|${[...v].sort().join(',')}|${a}`;
    const have = new Set(own.map((r) => key(r.match, r.values, r.action)));
    const rows = decoded.rules.map((r) => {
      const values = r.text.split(/[\s,]+/).filter(Boolean);
      const there = have.has(key(r.match, values, r.action));
      // A rule of yours above it that decides the same destinations differently.
      const probe = r.match === 'domain' ? values[0]?.replace(/^(full|domain|keyword|regexp):/, '') : null;
      const over = probe && rules && !there ? outcomeFor(probe, own, 'all', rules.localNamesDirect, nowSec()) : null;
      const overruled = over?.rule && over.rule.action !== r.action ? over.rule : null;
      return h(
        'tr',
        null,
        h('td', null, h('span', { class: `chip ${r.action}`, text: ACTION_LABEL[r.action] })),
        h('td', { class: 'ellipsis', title: values.join(', '), text: `${r.name ? `${r.name}: ` : ''}${values.slice(0, 4).join(', ')}${values.length > 4 ? ` +${values.length - 4}` : ''}` }),
        h('td', null, there ? h('span', { class: 'muted small', text: 'Already there' }) : overruled ? h('span', { class: 'chip warn', title: `Your rule “${overruled.name}” decides first when added after it`, text: 'Overruled' }) : h('span', { class: 'chip ok', text: 'New' })),
      );
    });
    preview.replaceChildren(
      h('div', { class: 'banner info' }, h('div', { class: 'grow small' }, h('b', { text: decoded.name }), h('div', { text: `${plural(decoded.rules.length, 'rule')} · ${decoded.mode === 'selected' ? 'only selected sites use the tunnel' : 'everything uses the tunnel'}. No servers, links or passwords.` }))),
      h('div', { class: 'table-wrap scroll-y' }, h('table', { class: 'table compact' }, h('tbody', null, ...rows))),
    );
  };
  input.oninput = showPreview;
  file.onchange = async () => {
    const f = file.files?.[0];
    file.value = '';
    if (!f) return;
    if (f.size > 600 * 1024) return toast('The file is too large to be a recipe.', 'error');
    input.value = await f.text();
    showPreview();
  };
  const run = async (append: boolean) => {
    if (!decoded) return;
    try {
      await saveSnapshot();
      const r = await call<{ count: number }>('replaceRules', { rules: decoded.rules, mode: decoded.mode, append });
      toast(`${plural(r.count, 'rule')} ${append ? 'added' : 'imported'}. Undo is in the Rules toolbar.`);
      d.close();
      await refreshRules();
      onApplied();
    } catch (e) {
      toast((e as Error).message, 'error');
    }
  };
  apply.onclick = async () => {
    if (await confirmDialog('Replace your rules?', 'Your own rules are replaced by the recipe. Presets stay. You can undo it.', 'Replace', true)) void run(false);
  };
  add.onclick = () => void run(true);
  const d = dialog(
    'Recipes',
    [
      h('p', { class: 'muted small', text: 'Share your rules as a line of text or a file. A recipe has no servers, links or passwords.' }),
      h('label', { class: 'field' }, h('span', { text: 'Your rules' }), h('textarea', { attrs: { rows: '3', readonly: '' }, style: { width: '100%' }, value: exportText })),
      h('div', { class: 'row' }, h('button', { class: 'btn', on: { click: () => void copyText(exportText, 'Recipe') } }, icon('copy', 14), 'Copy'), h('button', { class: 'btn', text: 'Save as a file…', on: { click: () => download(exportText, 'perroute-recipe.txt', 'text/plain') } })),
      h('hr'),
      h('label', { class: 'field' }, h('span', { text: 'Import a recipe' }), input),
      h('div', { class: 'row' }, h('button', { class: 'btn small', text: 'Open a file…', on: { click: () => file.click() } })),
      preview,
      file,
    ],
    [h('button', { class: 'btn', text: 'Close', on: { click: () => d.close() } }), add, apply],
    { wide: true },
  );
}

function presetDialog(s: Scenario, current: { killSwitch: boolean; autoFailover: boolean; fastestOnConnect: boolean } | null) {
  const change = (label: string, from: boolean | undefined, to: boolean) => h('li', { text: `${label}: ${from === to ? `stays ${to ? 'on' : 'off'}` : `${from ? 'on' : 'off'} → ${to ? 'on' : 'off'}`}` });
  const d = dialog(
    `${s.title} preset`,
    [
      h('p', { text: s.summary }),
      h('h3', { text: 'Settings' }),
      h('ul', { class: 'small' }, change('Kill switch', current?.killSwitch, s.killSwitch), change('Automatic failover', current?.autoFailover, s.autoFailover), change('Fastest server on connect', current?.fastestOnConnect, s.fastestOnConnect)),
      h('h3', { text: `Rules it adds above yours (${s.rules.length})` }),
      h('div', { class: 'table-wrap scroll-y' }, h('table', { class: 'table compact' }, h('tbody', null, ...s.rules.map((r) => h('tr', null, h('td', null, h('span', { class: `chip ${r.action}`, text: ACTION_LABEL[r.action] })), h('td', { text: r.name }), h('td', { class: 'muted small ellipsis', title: r.text, text: r.text })))))),
      h('p', { class: 'muted small', text: 'Your own rules, servers and routing mode are not changed. Removing the preset takes its rules out again.' }),
    ],
    [
      h('button', { class: 'btn', text: 'Cancel', on: { click: () => d.close() } }),
      actionButton('Apply', 'btn primary', async () => {
        await call('applyScenario', { id: s.id });
        toast(`${s.title} preset applied.`);
        d.close();
        await refreshRules();
      }),
    ],
    { wide: true },
  );
}

// ------------------------------------------------------------------ section

export const rulesSection: Section = {
  id: 'rules',
  title: 'Rules',
  subtitle: 'Choose which sites use the tunnel, open directly or are blocked. The first matching rule wins.',
  icon: 'rules',
  mount(el) {
    let scenarios: Scenario[] = [];
    let undo: Snapshot | null = null;
    let testHost = '';

    async function setMode(mode: RoutingMode) {
      const r = await request('setSettings', { routingMode: mode });
      if (!r.ok) toast(r.error.message, 'error');
      await refreshRules();
    }

    const modeCard = region(h('section', { class: 'card stack' }), () => {
      const rules = ctx.rules;
      const mode = rules?.mode ?? 'all';
      const seg = h('div', { class: 'segmented', role: 'group' });
      for (const [m, label] of [
        ['all', 'Everything through the tunnel'],
        ['selected', 'Only selected sites'],
      ] as const) {
        seg.append(h('button', { type: 'button', text: label, disabled: !isReady(), attrs: { 'aria-pressed': String(mode === m) }, on: { click: () => void setMode(m) } }));
      }
      return [
        h('div', { class: 'card-title' }, h('h2', { text: 'Routing' }), seg),
        h('p', { class: 'muted small', text: mode === 'all' ? 'All sites use the tunnel, except those your rules send directly or block.' : 'Sites open directly, except those your rules send through the tunnel.' }),
        h(
          'div',
          { class: 'setting' },
          h('div', { class: 'text' }, h('b', { text: 'Keep local names local' }), h('span', { text: 'printer.local, nas, router.lan and private addresses never go through the tunnel.' })),
          toggle(rules?.localNamesDirect ?? true, async (v) => {
            const r = await request('setSettings', { localNamesDirect: v });
            if (!r.ok) {
              toast(r.error.message, 'error');
              return false;
            }
            await refreshRules();
            return true;
          }, 'Keep local names local', !isReady()),
        ),
      ];
    }, () => [ctx.rules?.mode, ctx.rules?.localNamesDirect, isReady()]);

    const testCard = region(h('section', { class: 'card stack' }), () => {
      const input = h('input', { type: 'text', value: testHost, placeholder: 'A site or address, e.g. www.youtube.com', class: 'grow', ariaLabel: 'Site to check' });
      const out = h('div');
      const check = () => {
        testHost = input.value.trim();
        const rules = ctx.rules;
        if (!testHost || !rules) return out.replaceChildren();
        const url = testHost.includes('://') ? testHost : `https://${testHost}`;
        const host = hostOfUrl(url);
        if (!host) return out.replaceChildren(h('p', { class: 'small error-text', text: 'That is not a site or address.' }));
        let port: number | undefined;
        try {
          port = Number(new URL(url).port) || (url.startsWith('http:') ? 80 : 443);
        } catch {
          port = undefined;
        }
        const o = outcomeFor(host, rules.rules, rules.mode, rules.localNamesDirect, nowSec(), port);
        const why = o.local ? 'It is a local address.' : o.rule ? `Rule “${o.rule.name}” matches it${o.rule.serverId && o.outcome === 'proxy' ? `, through ${serverName(o.rule.serverId)}` : ''}.` : rules.mode === 'selected' ? 'No rule matches, and only selected sites use the tunnel.' : 'No rule matches, and everything uses the tunnel.';
        out.replaceChildren(h('div', { class: 'row wrap' }, h('span', { class: `chip ${o.outcome}`, text: o.outcome === 'proxy' ? 'Tunnel' : o.outcome === 'direct' ? 'Direct' : 'Blocked' }), h('span', { class: 'mono', text: host }), h('span', { class: 'muted small', text: why })));
      };
      input.onkeydown = (e) => e.key === 'Enter' && check();
      input.oninput = check;
      check();
      return [h('h3', { text: 'Where does a site go?' }), h('div', { class: 'row' }, input), out];
    }, () => [ctx.rules]);

    function shadowNote(s: Shadow | undefined): HTMLElement | null {
      if (!s) return null;
      return h('span', { class: 'chip warn', title: `“${s.by.name}” above it already matches everything it matches, so it never applies.`, text: 'Never applies' });
    }

    const rulesCard = region(h('section', { class: 'card stack' }), () => {
      const rules = ctx.rules?.rules ?? [];
      const own = rules.filter((r) => !r.scenario);
      const shadows = new Map(shadowedRules(rules, nowSec()).map((s) => [s.rule.id, s]));
      const tbody = h('tbody');
      rules.forEach((r) => {
        const preset = !!r.scenario;
        const values = h('div', { class: 'rule-values' }, ...r.values.slice(0, 6).map((v) => h('span', { class: 'chip', text: v })), r.values.length > 6 ? h('span', { class: 'muted small', text: `+${r.values.length - 6} more` }) : null);
        const active = ruleActive(r, nowSec());
        const then = h(
          'div',
          { class: 'stack', style: { gap: '2px', alignItems: 'flex-start' } },
          h('span', { class: `chip ${r.action}`, text: ACTION_LABEL[r.action] }),
          r.action === 'proxy' && r.serverId ? h('span', { class: 'muted small', text: serverName(r.serverId) }) : null,
          r.expiresAt ? h('span', { class: 'muted small', text: active ? `Ends in ${until(r.expiresAt)}` : 'Ended' }) : null,
        );
        const idx = own.findIndex((x) => x.id === r.id);
        const actions = preset
          ? h('span', { class: 'muted small', text: 'Preset' })
          : h(
              'div',
              { class: 'row', style: { justifyContent: 'flex-end', gap: '2px' } },
              h('button', { class: 'btn ghost icon small', title: 'Move up', ariaLabel: 'Move up', disabled: idx <= 0, on: { click: () => void call('moveRule', { id: r.id, offset: -1 }).then(refreshRules) } }, icon('up', 14)),
              h('button', { class: 'btn ghost icon small', title: 'Move down', ariaLabel: 'Move down', disabled: idx === own.length - 1, on: { click: () => void call('moveRule', { id: r.id, offset: 1 }).then(refreshRules) } }, icon('down', 14)),
              h('button', { class: 'btn ghost icon small', title: 'Edit', ariaLabel: 'Edit', on: { click: () => ruleDialog(r) } }, icon('edit', 14)),
              confirmButton('Delete', 'Delete?', 'btn small danger', async () => {
                const x = await request('deleteRule', { id: r.id });
                if (!x.ok) toast(x.error.message, 'error');
                await refreshRules();
              }),
            );
        tbody.append(
          h(
            'tr',
            { class: active ? '' : 'inactive' },
            h(
              'td',
              { class: 'toggle-cell' },
              preset
                ? null
                : toggle(
                    r.enabled,
                    async (v) => {
                      const x = await request('setRuleEnabled', { id: r.id, enabled: v });
                      if (!x.ok) {
                        toast(x.error.message, 'error');
                        return false;
                      }
                      await refreshRules();
                      return true;
                    },
                    r.enabled ? 'Turn off' : 'Turn on',
                  ),
            ),
            h('td', null, h('div', { class: 'row wrap', style: { gap: '6px' } }, h('span', { class: 'title', text: r.name }), shadowNote(shadows.get(r.id))), h('div', { class: 'muted small', text: MATCH_LABEL[r.match] }), values),
            h('td', null, then),
            h('td', null, actions),
          ),
        );
      });
      return [
        h(
          'div',
          { class: 'toolbar' },
          h('h2', { class: 'grow', text: `Rules${rules.length ? ` (${rules.length})` : ''}` }),
          undo
            ? confirmButton('Undo last recipe', 'Undo it?', 'btn', async () => {
                if (!undo) return;
                try {
                  // A server a rule used may have been removed since: then those rules use the main server.
                  const snap = undo;
                  await call('replaceRules', { rules: snap.rules, mode: snap.mode, append: false }).catch(() => call('replaceRules', { rules: snap.rules.map((x) => ({ ...x, serverId: null })), mode: snap.mode, append: false }));
                  await chrome.storage.local.remove('recipeUndo');
                  undo = null;
                  toast('Your rules are back as they were.');
                  await refreshRules();
                } catch (e) {
                  toast((e as Error).message, 'error');
                }
              })
            : null,
          h('button', { class: 'btn', disabled: !isReady(), on: { click: () => recipeDialog(() => void loadSnapshot().then((s) => ((undo = s), rulesCard.render(true)))) } }, icon('copy', 14), 'Recipes'),
          h('button', { class: 'btn primary', disabled: !isReady(), on: { click: () => ruleDialog(null) } }, icon('plus', 14), 'Add rule'),
        ),
        shadows.size ? h('div', { class: 'banner' }, h('div', { class: 'grow small', text: `${plural(shadows.size, 'rule')} never ${shadows.size === 1 ? 'applies' : 'apply'}: a rule above already decides the same sites. Move it up, or remove it.` })) : null,
        rules.length
          ? h('div', { class: 'table-wrap' }, h('table', { class: 'table rules' }, h('thead', null, h('tr', null, h('th', { text: 'On' }), h('th', { text: 'Rule' }), h('th', { text: 'Then' }), h('th'))), tbody))
          : h('div', { class: 'empty' }, icon('rules', 28), h('p', { text: 'No rules yet. Add one here, use a preset, or choose Tunnel, Direct or Block for a site in the toolbar popup.' })),
      ];
    }, () => [ctx.rules, ctx.list.servers.map((s) => [s.id, s.name]), isReady(), !!undo, Math.floor(Date.now() / 60000)]);

    const presetCard = region(h('section', { class: 'card stack' }), () => {
      const active = ctx.rules?.activeScenario ?? null;
      const s = ctx.app?.settings;
      return [
        h('div', { class: 'card-title' }, h('h2', { text: 'Presets' }), active ? actionButton('Remove preset', 'btn small', async () => void (await call('clearScenario', {}), await refreshRules())) : null),
        h('p', { class: 'muted small', text: 'A preset sets protection options and adds a few rules above your own. Removing it keeps your rules.' }),
        h(
          'div',
          { class: 'grid3' },
          ...scenarios.map((sc) =>
            h(
              'div',
              { class: 'card flat scenario', data: { active: String(active === sc.id) } },
              h('div', { class: 'row' }, h('h3', { class: 'grow', text: sc.title }), active === sc.id ? h('span', { class: 'chip lime', text: 'Active' }) : null),
              h('p', { class: 'small', text: sc.summary }),
              h('ul', null, ...sc.highlights.map((x) => h('li', { text: x }))),
              h('button', {
                class: `btn small ${active === sc.id ? '' : 'primary'}`,
                text: active === sc.id ? 'Applied' : 'Preview and apply',
                disabled: active === sc.id || !isReady(),
                on: { click: () => presetDialog(sc, s ? { killSwitch: s.killSwitch, autoFailover: s.autoFailover, fastestOnConnect: s.fastestOnConnect } : null) },
              }),
            ),
          ),
        ),
      ];
    }, () => [scenarios, ctx.rules?.activeScenario, isReady(), ctx.app?.settings]);

    el.append(modeCard.el, testCard.el, rulesCard.el, presetCard.el);

    async function loadScenarios() {
      if (!isReady()) return;
      const r = await request('listScenarios', {});
      if (r.ok) scenarios = r.result as Scenario[];
      presetCard.render();
    }

    function update() {
      modeCard.render();
      testCard.render();
      rulesCard.render();
      presetCard.render();
      if (!scenarios.length) void loadScenarios();
    }
    update();
    void loadSnapshot().then((s) => {
      undo = s;
      rulesCard.render(true);
    });
    return { update };
  },
};
