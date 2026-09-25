/* The words and the tone of the Billing & Revenue desk.
 *
 * A bill's situation is computed once in billing and carries its own reason;
 * nothing here recomputes lateness or an amount. This file only decides how
 * loudly each situation is said. The paper's rule: exceptions are loud, normal
 * financial states are calm, and a clean book reads as clean.
 */

export const SITUATIONS = {
  overdue: { label: 'Overdue', tone: 'bad', exception: true },
  disputed: { label: 'In dispute', tone: 'bad', exception: true },
  arrangement: { label: 'Arrangement', tone: 'warn', exception: true },
  partiallyPaid: { label: 'Partly paid', tone: 'calm' },
  outstanding: { label: 'Outstanding', tone: 'calm', exception: true },
  issued: { label: 'Issued', tone: 'calm' },
  paid: { label: 'Paid', tone: 'quiet' },
  writtenOff: { label: 'Written off', tone: 'quiet' },
};

/** The chips above the table, in the order a finance operator scans them. */
export const CHIPS = ['overdue', 'disputed', 'arrangement', 'outstanding'];

/** What a chip says when its count is zero — a clean state, never a warning. */
export const CLEAN = {
  overdue: 'No overdue bills',
  disputed: 'Nothing in dispute',
  arrangement: 'No arrangements',
  outstanding: 'Nothing outstanding',
};

export const situationOf = (bill) => (bill && bill.billSituation) || {};
export const labelOf = (value) => (SITUATIONS[value] || {}).label || value || '—';
export const toneOf = (value) => (SITUATIONS[value] || {}).tone || 'calm';

/** A date as a person says it: 8 October 2026. Never an ISO string on screen. */
export function day(value) {
  if (!value) return '—';
  const at = new Date(value);
  if (Number.isNaN(at.getTime())) return String(value).slice(0, 10);
  return at.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

export function moment(value) {
  if (!value) return '—';
  const at = new Date(value);
  if (Number.isNaN(at.getTime())) return String(value);
  return at.toLocaleString(undefined, { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

/** Money as the API states it; the unit is the tenant's, never assumed. */
export function money(m) {
  if (!m || m.value === null || m.value === undefined) return '—';
  const n = Number(m.value);
  return `${Number.isFinite(n) ? n.toFixed(2) : m.value} ${m.unit || ''}`.trim();
}

export function period(p) {
  if (!p || (!p.startDateTime && !p.endDateTime)) return '—';
  return `${day(p.startDateTime)} → ${day(p.endDateTime)}`;
}


/** An identifier is never the headline; it belongs under technical details.
 *  The ledger writes its own descriptions ("Cash received — 0b3ae95d-…"), so
 *  the desk says the business half and leaves the reference below. */
export const shortId = (id) => (id ? String(id).slice(0, 8) : '');

const ID = /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi;
/** A millisecond epoch the seeds append to make a name unique, dash and all. */
const STAMP = /-?\b1[0-9]{12}\b/g;

/** An ISO day, with or without a time after it. */
const ISO_DAY = /\b\d{4}-\d{2}-\d{2}(?:T[\d:.]+Z?)?\b/g;

/** How a sentence written by a service is said on screen. Services write their
 *  own prose — "Overdue since 2026-09-23", "Cash received — 0b3ae95d-…" — so
 *  this strips the identifiers, says the dates the way a person says them, and
 *  tidies what removing an identifier leaves behind: a space inside a bracket,
 *  a space before a comma, a dangling dash, an empty bracket. It removes
 *  identifiers only — never a word and never an amount. */
export function plain(text) {
  if (!text) return '';
  return String(text)
    .replace(ID, '')
    .replace(ISO_DAY, (m) => day(m))
    .replace(STAMP, '')
    .replace(/\(\s+/g, '(')
    .replace(/\s+\)/g, ')')
    .replace(/\s*\(\s*\)/g, '')
    .replace(/\s+([,.;:])/g, '$1')
    .replace(/[\s—–-]+$/, '')
    .replace(/\s{2,}/g, ' ')
    .trim();
}
