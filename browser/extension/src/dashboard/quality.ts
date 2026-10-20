// A server's network quality score and what it is made of, as in the desktop app's server panel.
import type { QualityLabel, QualityScore } from '../../../protocol/types';
import { h } from '../shared/dom';
import { ago } from '../shared/format';

export const QUALITY_TEXT: Record<QualityLabel, string> = {
  excellent: 'Excellent',
  good: 'Good',
  fair: 'Fair',
  poor: 'Poor',
  unavailable: 'Unavailable',
  unknown: 'Unknown',
};

export function qualityChip(score: number | null, label: QualityLabel, stale = false): HTMLElement {
  return h('span', { class: `chip ${label}${stale ? ' stale' : ''}`, title: stale ? 'Measured a while ago' : QUALITY_TEXT[label], text: score === null ? QUALITY_TEXT[label] : `${score} · ${QUALITY_TEXT[label]}` });
}

function part(name: string, value: string, share: number | null, hint: string): HTMLElement {
  return h(
    'div',
    { class: 'q-part', title: hint },
    h('span', { class: 'q-name', text: name }),
    h('span', { class: 'q-bar' }, share === null ? null : h('i', { style: { width: `${Math.round(Math.max(0, Math.min(1, share)) * 100)}%` } })),
    h('span', { class: 'q-value', text: value }),
  );
}

/** Score, headline, explanation and the parts of the score. */
export function qualityDetails(q: QualityScore): HTMLElement {
  const c = q.components;
  const ms = (v: number | null) => (v === null ? '—' : `${Math.round(v)} ms`);
  // Bars show the part scores (0–100, higher is better).
  const bar = (v: number | null | undefined) => (v === null || v === undefined ? null : v / 100);
  return h(
    'div',
    { class: 'quality stack' },
    h('div', { class: 'row wrap' }, qualityChip(q.score, q.label, q.stale), h('span', { class: 'muted small', text: `${Math.round(q.confidence * 100)}% confidence · ${q.sampleCount} ${q.sampleCount === 1 ? 'sample' : 'samples'} · measured ${ago(q.measuredAt / (q.measuredAt > 1e12 ? 1000 : 1))}${q.tunnelVerified ? ' · tunnel verified' : ''}` })),
    q.headline ? h('b', { class: 'small', text: q.headline }) : null,
    h('p', { class: 'muted small', text: q.explanation }),
    h(
      'div',
      { class: 'q-parts' },
      part('Latency', ms(c.latencyMs), bar(c.latencyScore), 'Time for a round trip through the server'),
      part('Jitter', ms(c.jitterMs), bar(c.jitterScore), 'How much the latency varies'),
      part('Success', c.successRate === null ? '—' : `${Math.round(c.successRate * 100)}%`, bar(c.successScore ?? (c.successRate === null ? null : c.successRate * 100)), 'Share of tests that worked'),
      part('Stability', c.stabilityScore === null ? '—' : `${Math.round(c.stabilityScore)}`, bar(c.stabilityScore), 'How steady the server was over time'),
      part('Tunnel setup', ms(c.setupMs), bar(c.setupScore), 'Time to open a tunnel'),
    ),
    c.recentFailures || c.recoveryPenalty ? h('p', { class: 'small warn-text', text: `${c.recentFailures} recent ${c.recentFailures === 1 ? 'failure' : 'failures'}${c.recoveryPenalty ? `, −${Math.round(c.recoveryPenalty)} points` : ''}.` }) : null,
  );
}
