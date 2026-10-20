import type { ProfileList, ProfileSummary } from '../../../../protocol/types';
import { call, request } from '../../shared/client';
import { actionButton, confirmButton, h, region, toast } from '../../shared/dom';
import { date, plural } from '../../shared/format';
import { icon } from '../../shared/icons';
import { activeServerId, ctx, isReady, refreshList, refreshRules, serverName } from '../context';
import type { Section } from '../section';

export const profilesSection: Section = {
  id: 'profiles',
  title: 'Profiles',
  subtitle: 'Save a server, routing mode and rules together, and switch between them.',
  icon: 'profiles',
  mount(el) {
    let profiles: ProfileSummary[] = [];
    let activeId: string | null = null;
    let loaded = false;
    // Created once, so what the user types survives every state update.
    const name = h('input', { type: 'text', placeholder: 'Work, Home, Travel…', class: 'grow', ariaLabel: 'Profile name' });

    async function load() {
      if (!isReady()) return;
      const r = await request('listProfiles', {});
      if (r.ok) {
        const x = r.result as ProfileList;
        profiles = x.profiles;
        activeId = x.activeProfileId;
        loaded = true;
      }
      listCard.render();
    }

    async function save() {
      const n = name.value.trim();
      if (!n) return void name.focus();
      const replacing = profiles.some((p) => p.name.toLowerCase() === n.toLowerCase());
      await call('saveProfile', { name: n });
      toast(replacing ? `Profile “${n}” updated.` : `Profile “${n}” saved.`);
      name.value = '';
      await load();
    }
    name.onkeydown = (e) => {
      if (e.key === 'Enter') void save().catch((err: Error) => toast(err.message, 'error'));
    };

    const saveCard = region(h('section', { class: 'card stack' }), () => {
      const rules = ctx.rules?.rules.filter((r) => !r.scenario && r.expiresAt === null).length ?? 0;
      const sid = activeServerId() ?? ctx.list.selectedServerId;
      return [
        h('h2', { text: 'Save the current setup' }),
        h('p', { class: 'muted small', text: `Saves ${sid ? `the server “${serverName(sid)}”` : 'no server'}, the routing mode (${ctx.rules?.mode === 'selected' ? 'only selected sites' : 'everything'} through the tunnel) and ${plural(rules, 'rule')}. Saving with an existing name replaces that profile.` }),
        h('div', { class: 'row' }, name, actionButton('Save', 'btn primary', save, 'Saving…', { disabled: !isReady() })),
      ];
    }, () => [activeServerId(), ctx.list.selectedServerId, ctx.list.servers.length, ctx.rules?.mode, ctx.rules?.rules.length, isReady()]);

    const listCard = region(h('section', { class: 'card stack' }), () => [
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
                actionButton(
                  p.id === activeId ? 'Apply again' : 'Switch',
                  `btn small ${p.id === activeId ? '' : 'primary'}`,
                  async () => {
                    await call('applyProfile', { id: p.id });
                    toast(`Switched to ${p.name}.`);
                    await Promise.all([load(), refreshRules(), refreshList()]);
                  },
                  'Switching…',
                  { disabled: !isReady() },
                ),
                confirmButton('Delete', 'Delete?', 'btn small danger', async () => {
                  const r = await request('deleteProfile', { id: p.id });
                  if (!r.ok) toast(r.error.message, 'error');
                  await load();
                }),
              ),
            ),
          )
        : h('div', { class: 'empty' }, icon('profiles', 28), h('p', { text: loaded ? 'No profiles yet. Save the current setup above.' : 'Loading…' })),
    ], () => [profiles, activeId, loaded, ctx.list.servers.map((s) => [s.id, s.name]), isReady()]);

    el.append(saveCard.el, listCard.el);
    saveCard.render();
    listCard.render();
    void load();
    return {
      update() {
        saveCard.render();
        listCard.render();
        if (isReady() && !loaded) void load();
      },
    };
  },
};
