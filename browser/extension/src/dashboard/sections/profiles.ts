import type { ProfileSummary } from '../../../../protocol/types';
import { call, request } from '../../shared/client';
import { clear, confirmButton, h, toast } from '../../shared/dom';
import { date, plural } from '../../shared/format';
import { ctx, isReady, refreshList, refreshRules, serverName } from '../context';
import type { Section } from '../section';

export const profilesSection: Section = {
  id: 'profiles',
  title: 'Profiles',
  subtitle: 'Save a server, routing mode and rules together, and switch between them.',
  icon: 'profiles',
  mount(el) {
    let profiles: ProfileSummary[] = [];
    let activeId: string | null = null;
    const saveCard = h('section', { class: 'card stack' });
    const listCard = h('section', { class: 'card stack' });
    el.append(saveCard, listCard);

    async function load() {
      if (!isReady()) return;
      const r = await request('listProfiles', {});
      if (r.ok) {
        const x = r.result as { profiles: ProfileSummary[]; activeProfileId: string | null };
        profiles = x.profiles;
        activeId = x.activeProfileId;
      }
      renderList();
    }

    function renderSave() {
      const name = h('input', { type: 'text', placeholder: 'Work, Home, Travel…', style: { flex: '1' } });
      const save = async () => {
        try {
          await call('saveProfile', { name: name.value.trim() });
          toast('Profile saved.');
          name.value = '';
          await load();
        } catch (e) {
          toast((e as Error).message, 'error');
        }
      };
      name.onkeydown = (e) => e.key === 'Enter' && void save();
      const rules = ctx.rules?.rules.filter((r) => !r.scenario && r.expiresAt === null).length ?? 0;
      clear(
        saveCard,
        h('h2', { text: 'Save the current setup' }),
        h('p', { class: 'muted small', text: `Saves the ${ctx.app?.status?.serverId ? 'connected' : 'selected'} server, the routing mode and ${plural(rules, 'rule')}. Saving with an existing name replaces that profile.` }),
        h('div', { class: 'row' }, name, h('button', { class: 'btn primary', text: 'Save', disabled: !isReady(), on: { click: () => void save() } })),
      );
    }

    function renderList() {
      clear(
        listCard,
        h('h2', { text: 'Profiles' }),
        profiles.length
          ? h(
              'div',
              { class: 'list' },
              ...profiles.map((p) =>
                h(
                  'div',
                  { class: 'list-row' },
                  h(
                    'div',
                    { class: 'grow' },
                    h('div', { class: 'row' }, h('span', { class: 'title', text: p.name }), p.id === activeId ? h('span', { class: 'chip lime', text: 'Active' }) : null),
                    h('div', { class: 'sub', text: `${p.serverId ? serverName(p.serverId) : 'No server'} · ${p.routingMode === 'selected' ? 'only selected sites' : 'everything'} through the tunnel · ${plural(p.rules, 'rule')} · saved ${date(p.createdAt)}` }),
                  ),
                  h('button', {
                    class: 'btn small primary',
                    text: 'Switch',
                    on: {
                      click: async () => {
                        try {
                          await call('applyProfile', { id: p.id });
                          toast(`Switched to ${p.name}.`);
                          await Promise.all([load(), refreshRules(), refreshList()]);
                        } catch (e) {
                          toast((e as Error).message, 'error');
                        }
                      },
                    },
                  }),
                  confirmButton('Delete', 'Delete?', 'btn small danger', async () => {
                    const r = await request('deleteProfile', { id: p.id });
                    if (!r.ok) toast(r.error.message, 'error');
                    await load();
                  }),
                ),
              ),
            )
          : h('div', { class: 'empty', text: 'No profiles yet.' }),
      );
    }

    renderSave();
    renderList();
    void load();
    return {
      update() {
        renderSave();
        if (isReady() && profiles.length === 0) void load();
      },
    };
  },
};
