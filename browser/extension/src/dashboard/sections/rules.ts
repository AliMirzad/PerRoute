import type { RoutingMode, RuleAction, RuleInput, RuleMatch, Scenario, SiteRule } from '../../../../protocol/types';
import { call, request } from '../../shared/client';
import { clear, confirmButton, h, toast, toggle } from '../../shared/dom';
import { DURATIONS, durationLabel, until } from '../../shared/format';
import { icon } from '../../shared/icons';
import { decodeRecipe, encodeRecipe } from '../../shared/recipe';
import { ctx, isReady, refreshRules, serverName, sortedServers } from '../context';
import type { Section } from '../section';

const ACTION_LABEL: Record<RuleAction, string> = { proxy: 'Tunnel', direct: 'Direct', block: 'Block' };
const MATCH_LABEL: Record<RuleMatch, string> = { domain: 'Sites', ip: 'IP addresses', port: 'Ports' };
const MATCH_HINT: Record<RuleMatch, string> = {
  domain: 'youtube.com, *.example.org, a full URL, full:api.example.com, keyword:ads',
  ip: '1.2.3.4, 10.0.0.0/8, 2001:db8::/32',
  port: '443, 6881-6889',
};

function ruleDialog(existing: SiteRule | null, prefill?: Partial<RuleInput>) {
  const name = h('input', { type: 'text', value: existing?.name ?? prefill?.name ?? '', placeholder: 'Optional' });
  const match = h('select');
  for (const m of ['domain', 'ip', 'port'] as RuleMatch[]) match.append(h('option', { value: m, text: MATCH_LABEL[m] }));
  match.value = existing?.match ?? prefill?.match ?? 'domain';
  const text = h('textarea', { attrs: { rows: '4' }, style: { width: '100%' } });
  text.value = existing ? existing.values.join('\n') : (prefill?.text ?? '');
  const hint = h('div', { class: 'muted small' });
  const action = h('div', { class: 'segmented' });
  let chosen: RuleAction = existing?.action ?? prefill?.action ?? 'proxy';
  const server = h('select');
  server.append(h('option', { value: '', text: 'Main server' }));
  for (const s of sortedServers()) server.append(h('option', { value: s.id, text: s.name }));
  server.value = existing?.serverId ?? '';
  const serverField = h('label', { class: 'field' }, h('span', { text: 'Server' }), server);
  const duration = h('select');
  duration.append(h('option', { value: '0', text: 'Always' }));
  for (const m of DURATIONS) duration.append(h('option', { value: String(m), text: `For ${durationLabel(m)}` }));
  const renderAction = () => {
    clear(action);
    for (const a of ['proxy', 'direct', 'block'] as RuleAction[]) {
      action.append(h('button', { text: ACTION_LABEL[a], attrs: { 'aria-pressed': String(chosen === a) }, on: { click: () => ((chosen = a), renderAction()) } }));
    }
    serverField.hidden = chosen !== 'proxy';
  };
  const renderHint = () => (hint.textContent = `One per line or separated by commas. Examples: ${MATCH_HINT[match.value as RuleMatch]}`);
  match.onchange = renderHint;
  renderAction();
  renderHint();
  const save = h('button', { class: 'btn primary', text: existing ? 'Save' : 'Add rule' });
  const err = h('p', { class: 'small', style: { color: 'var(--danger)' }, hidden: true });
  const d = h(
    'dialog',
    null,
    h(
      'div',
      { class: 'stack' },
      h('h2', { text: existing ? 'Edit rule' : 'New rule' }),
      h('div', { class: 'row' }, h('label', { class: 'field grow' }, h('span', { text: 'Name' }), name), h('label', { class: 'field' }, h('span', { text: 'Match' }), match)),
      h('label', { class: 'field' }, h('span', { text: 'Entries' }), text, hint),
      h('div', { class: 'field' }, h('span', { text: 'Then' }), action),
      h('div', { class: 'row' }, serverField, h('label', { class: 'field' }, h('span', { text: 'Duration' }), duration)),
      err,
      h('div', { class: 'row', style: { justifyContent: 'flex-end' } }, h('button', { class: 'btn', text: 'Cancel', on: { click: () => d.close() } }), save),
    ),
  );
  d.addEventListener('close', () => d.remove());
  document.body.append(d);
  d.showModal();
  save.onclick = async () => {
    const minutes = Number(duration.value);
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

function recipeDialog() {
  const rules = ctx.rules;
  const exportText = rules ? encodeRecipe('My rules', rules.mode, rules.rules.filter((r) => !r.scenario && r.expiresAt === null)) : '';
  const input = h('textarea', { attrs: { rows: '4' }, style: { width: '100%' }, placeholder: 'Paste a PerRoute recipe' });
  const preview = h('div', { class: 'stack' });
  let decoded: ReturnType<typeof decodeRecipe> | null = null;
  input.oninput = () => {
    try {
      decoded = input.value.trim() ? decodeRecipe(input.value) : null;
      clear(
        preview,
        decoded
          ? h(
              'div',
              { class: 'banner info' },
              h(
                'div',
                { class: 'grow small' },
                h('b', { text: decoded.name }),
                h('div', { text: `${decoded.rules.length} rules · ${decoded.mode === 'selected' ? 'only selected sites use the tunnel' : 'everything uses the tunnel'}. No servers, links or passwords are included.` }),
                ...decoded.rules.slice(0, 6).map((r) => h('div', { class: 'muted', text: `${ACTION_LABEL[r.action]}: ${r.text.split(/[\s,]+/).slice(0, 4).join(', ')}` })),
              ),
            )
          : null,
      );
    } catch (e) {
      decoded = null;
      clear(preview, h('p', { class: 'small', style: { color: 'var(--danger)' }, text: (e as Error).message }));
    }
  };
  const apply = async (append: boolean) => {
    if (!decoded) return;
    try {
      const r = await call<{ count: number }>('replaceRules', { rules: decoded.rules, mode: decoded.mode, append });
      toast(`${r.count} rules ${append ? 'added' : 'imported'}.`);
      d.close();
      await refreshRules();
    } catch (e) {
      toast((e as Error).message, 'error');
    }
  };
  const d = h(
    'dialog',
    null,
    h(
      'div',
      { class: 'stack' },
      h('h2', { text: 'Recipes' }),
      h('p', { class: 'muted small', text: 'Share your rules as a line of text. A recipe has no servers, links or passwords.' }),
      h('label', { class: 'field' }, h('span', { text: 'Your rules' }), h('textarea', { attrs: { rows: '3', readonly: '' }, style: { width: '100%' }, value: exportText })),
      h('div', { class: 'row' }, h('button', { class: 'btn', text: 'Copy', on: { click: () => void navigator.clipboard.writeText(exportText).then(() => toast('Recipe copied.')) } })),
      h('label', { class: 'field' }, h('span', { text: 'Import a recipe' }), input),
      preview,
      h(
        'div',
        { class: 'row', style: { justifyContent: 'flex-end' } },
        h('button', { class: 'btn', text: 'Close', on: { click: () => d.close() } }),
        h('button', { class: 'btn', text: 'Add to my rules', on: { click: () => void apply(true) } }),
        h('button', { class: 'btn primary', text: 'Replace my rules', on: { click: () => void apply(false) } }),
      ),
    ),
  );
  d.addEventListener('close', () => d.remove());
  document.body.append(d);
  d.showModal();
}

export const rulesSection: Section = {
  id: 'rules',
  title: 'Rules',
  subtitle: 'Choose which sites use the tunnel, open directly or are blocked. The first matching rule wins.',
  icon: 'rules',
  mount(el) {
    let scenarios: Scenario[] = [];
    const modeCard = h('section', { class: 'card stack' });
    const presetCard = h('section', { class: 'card stack' });
    const rulesCard = h('section', { class: 'card stack' });
    el.append(modeCard, rulesCard, presetCard);

    async function setMode(mode: RoutingMode) {
      const r = await request('setSettings', { routingMode: mode });
      if (!r.ok) toast(r.error.message, 'error');
      await refreshRules();
    }

    function renderMode() {
      const rules = ctx.rules;
      const mode = rules?.mode ?? 'all';
      const seg = h('div', { class: 'segmented' });
      for (const [m, label] of [
        ['all', 'Everything through the tunnel'],
        ['selected', 'Only selected sites'],
      ] as const) {
        seg.append(h('button', { text: label, disabled: !isReady(), attrs: { 'aria-pressed': String(mode === m) }, on: { click: () => void setMode(m) } }));
      }
      clear(
        modeCard,
        h('div', { class: 'card-title' }, h('h2', { text: 'Routing' }), seg),
        h('p', { class: 'muted small', text: mode === 'all' ? 'All sites use the tunnel, except those your rules send directly or block.' : 'Sites open directly, except those your rules send through the tunnel.' }),
        h(
          'div',
          { class: 'setting' },
          h('div', { class: 'text' }, h('b', { text: 'Keep local names local' }), h('span', { text: 'printer.local, nas, router.lan and private addresses never go through the tunnel.' })),
          toggle(rules?.localNamesDirect ?? true, async (v) => {
            const r = await request('setSettings', { localNamesDirect: v });
            if (!r.ok) toast(r.error.message, 'error');
            await refreshRules();
          }),
        ),
      );
    }

    function renderRules() {
      const rules = ctx.rules?.rules ?? [];
      const own = rules.filter((r) => !r.scenario);
      const table = h('table', { class: 'table' });
      table.append(h('tr', null, h('th', { text: 'On' }), h('th', { text: 'Rule' }), h('th', { text: 'Then' }), h('th', { text: '' })));
      rules.forEach((r) => {
        const preset = !!r.scenario;
        const values = h('div', { class: 'rule-values' }, ...r.values.slice(0, 6).map((v) => h('span', { class: 'chip', text: v })), r.values.length > 6 ? h('span', { class: 'muted small', text: `+${r.values.length - 6} more` }) : null);
        const then = h(
          'div',
          { class: 'stack', style: { gap: '2px' } },
          h('span', { class: `chip ${r.action}`, text: ACTION_LABEL[r.action] }),
          r.action === 'proxy' && r.serverId ? h('span', { class: 'muted small', text: serverName(r.serverId) }) : null,
          r.expiresAt ? h('span', { class: 'muted small', text: r.active ? `Ends in ${until(r.expiresAt)}` : 'Ended' }) : null,
        );
        const idx = own.findIndex((x) => x.id === r.id);
        const actions = preset
          ? h('span', { class: 'muted small', text: 'Preset' })
          : h(
              'div',
              { class: 'row', style: { justifyContent: 'flex-end', gap: '2px' } },
              h('button', { class: 'btn ghost icon small', title: 'Move up', disabled: idx <= 0, on: { click: () => void call('moveRule', { id: r.id, offset: -1 }).then(refreshRules) } }, icon('up', 14)),
              h('button', { class: 'btn ghost icon small', title: 'Move down', disabled: idx === own.length - 1, on: { click: () => void call('moveRule', { id: r.id, offset: 1 }).then(refreshRules) } }, icon('down', 14)),
              h('button', { class: 'btn ghost icon small', title: 'Edit', on: { click: () => ruleDialog(r) } }, icon('edit', 14)),
              confirmButton('Delete', 'Delete?', 'btn small danger', async () => {
                const x = await request('deleteRule', { id: r.id });
                if (!x.ok) toast(x.error.message, 'error');
                await refreshRules();
              }),
            );
        table.append(
          h(
            'tr',
            null,
            h(
              'td',
              { style: { width: '50px' } },
              preset
                ? null
                : toggle(
                    r.enabled,
                    async (v) => {
                      const x = await request('setRuleEnabled', { id: r.id, enabled: v });
                      if (!x.ok) toast(x.error.message, 'error');
                      await refreshRules();
                    },
                    'Enabled',
                  ),
            ),
            h('td', null, h('div', { class: 'title', text: r.name }), h('div', { class: 'muted small', text: MATCH_LABEL[r.match] }), values),
            h('td', null, then),
            h('td', null, actions),
          ),
        );
      });
      clear(
        rulesCard,
        h(
          'div',
          { class: 'toolbar' },
          h('h2', { class: 'grow', text: 'Rules' }),
          h('button', { class: 'btn', text: 'Recipes', disabled: !isReady(), on: { click: () => recipeDialog() } }),
          h('button', { class: 'btn primary', disabled: !isReady(), on: { click: () => ruleDialog(null) } }, icon('plus', 14), 'Add rule'),
        ),
        rules.length ? h('div', { class: 'table-wrap' }, table) : h('div', { class: 'empty', text: 'No rules yet. Add one here, or use the toolbar button on any site.' }),
      );
    }

    function renderPresets() {
      const active = ctx.rules?.activeScenario ?? null;
      clear(
        presetCard,
        h('div', { class: 'card-title' }, h('h2', { text: 'Presets' }), active ? h('button', { class: 'btn small', text: 'Remove preset', on: { click: () => void call('clearScenario', {}).then(refreshRules) } }) : null),
        h('p', { class: 'muted small', text: 'A preset sets protection options and adds a few rules above your own. Removing it keeps your rules.' }),
        h(
          'div',
          { class: 'grid3' },
          ...scenarios.map((s) =>
            h(
              'div',
              { class: 'card flat scenario', data: { active: String(active === s.id) } },
              h('div', { class: 'row' }, h('h3', { class: 'grow', text: s.title }), active === s.id ? h('span', { class: 'chip lime', text: 'Active' }) : null),
              h('p', { class: 'small', text: s.summary }),
              h('ul', null, ...s.highlights.map((x) => h('li', { text: x }))),
              h('button', {
                class: `btn small ${active === s.id ? '' : 'primary'}`,
                text: active === s.id ? 'Applied' : 'Apply',
                disabled: active === s.id || !isReady(),
                on: {
                  click: async () => {
                    try {
                      await call('applyScenario', { id: s.id });
                      toast(`${s.title} preset applied.`);
                      await refreshRules();
                    } catch (e) {
                      toast((e as Error).message, 'error');
                    }
                  },
                },
              }),
            ),
          ),
        ),
      );
    }

    async function loadScenarios() {
      const r = await request('listScenarios', {});
      if (r.ok) scenarios = r.result as Scenario[];
      renderPresets();
    }

    function update() {
      renderMode();
      renderRules();
      renderPresets();
    }
    update();
    void loadScenarios();
    return { update };
  },
};
