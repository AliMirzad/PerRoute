// Recipes: rules shared as one line of text. A recipe holds rule names, entries and actions and the
// routing mode; never servers, links, passwords or per-rule server choices. Whatever is decoded here
// is still validated by the helper when it is applied.
import type { RoutingMode, RuleAction, RuleInput, RuleMatch, SiteRule } from '../../../protocol/types';

const PREFIX = 'perroute-recipe:';
const MAX_TEXT = 512 * 1024;
const MAX_RULES = 500;

interface RecipeJson {
  v: 1;
  name: string;
  mode: RoutingMode;
  rules: { name: string; match: RuleMatch; text: string; action: RuleAction }[];
}

function toBase64Url(s: string): string {
  const bytes = new TextEncoder().encode(s);
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function fromBase64Url(s: string): string {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/');
  const bin = atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4));
  return new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)));
}

export function encodeRecipe(name: string, mode: RoutingMode, rules: SiteRule[]): string {
  const body: RecipeJson = { v: 1, name, mode, rules: rules.map((r) => ({ name: r.name, match: r.match, text: r.values.join(', '), action: r.action })) };
  return PREFIX + toBase64Url(JSON.stringify(body));
}

export function decodeRecipe(text: string): { name: string; mode: RoutingMode; rules: RuleInput[] } {
  const t = text.trim();
  if (t.length > MAX_TEXT) throw new Error('This recipe is too large.');
  if (!t.startsWith(PREFIX)) throw new Error('This is not a PerRoute recipe.');
  let j: RecipeJson;
  try {
    j = JSON.parse(fromBase64Url(t.slice(PREFIX.length)));
  } catch {
    throw new Error('This recipe is damaged.');
  }
  if (!j || j.v !== 1 || !Array.isArray(j.rules)) throw new Error('This recipe is damaged or from a newer version.');
  if (j.rules.length > MAX_RULES) throw new Error(`A recipe can have at most ${MAX_RULES} rules.`);
  const matches: RuleMatch[] = ['domain', 'ip', 'port'];
  const actions: RuleAction[] = ['proxy', 'direct', 'block'];
  const rules: RuleInput[] = j.rules.map((r) => {
    if (!r || typeof r.text !== 'string' || !matches.includes(r.match) || !actions.includes(r.action)) throw new Error('This recipe contains an invalid rule.');
    return { name: typeof r.name === 'string' ? r.name.slice(0, 100) : '', match: r.match, text: r.text, action: r.action };
  });
  return { name: typeof j.name === 'string' && j.name.trim() ? j.name.slice(0, 100) : 'Recipe', mode: j.mode === 'selected' ? 'selected' : 'all', rules };
}
