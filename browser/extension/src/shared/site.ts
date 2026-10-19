// The "site" of a host: the part a person means by "this website" (youtube.com for
// m.youtube.com, example.co.uk for www.example.co.uk). A small list of common second-level
// registries is enough here: the user can always pick the full host instead.
import { isIp } from './rules-eval';

const SECOND_LEVEL = new Set([
  'co.uk', 'org.uk', 'ac.uk', 'gov.uk', 'me.uk', 'ltd.uk', 'plc.uk',
  'com.au', 'net.au', 'org.au', 'edu.au', 'gov.au',
  'co.jp', 'ne.jp', 'or.jp', 'ac.jp', 'go.jp',
  'co.kr', 'or.kr', 'ac.kr',
  'co.in', 'net.in', 'org.in', 'ac.in', 'gov.in',
  'co.ir', 'ac.ir', 'org.ir', 'gov.ir', 'id.ir', 'net.ir', 'sch.ir',
  'com.tr', 'org.tr', 'net.tr', 'gov.tr', 'edu.tr',
  'com.br', 'net.br', 'org.br', 'gov.br',
  'com.cn', 'net.cn', 'org.cn', 'gov.cn', 'edu.cn',
  'com.hk', 'org.hk', 'com.tw', 'org.tw', 'com.sg', 'com.my',
  'co.nz', 'org.nz', 'co.za', 'org.za', 'co.il', 'org.il',
  'com.mx', 'com.ar', 'com.co', 'com.ua', 'com.ru', 'com.pk', 'com.sa', 'com.eg', 'com.vn',
  'github.io', 'gitlab.io', 'pages.dev', 'vercel.app', 'netlify.app', 'herokuapp.com', 'appspot.com',
  'blogspot.com', 'workers.dev', 'web.app', 'firebaseapp.com', 'azurewebsites.net', 'cloudfront.net',
]);

export function siteOf(host: string): string {
  const h = host.toLowerCase().replace(/\.$/, '');
  if (isIp(h) || !h.includes('.')) return h;
  const labels = h.split('.');
  if (labels.length <= 2) return h;
  const last2 = labels.slice(-2).join('.');
  return SECOND_LEVEL.has(last2) ? labels.slice(-3).join('.') : last2;
}

/** The host of a tab URL, or null for pages that are not websites (chrome://, files, the store). */
export function hostOfUrl(url: string | undefined): string | null {
  if (!url) return null;
  try {
    const u = new URL(url);
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
    return u.hostname.replace(/^\[|\]$/g, '') || null;
  } catch {
    return null;
  }
}
