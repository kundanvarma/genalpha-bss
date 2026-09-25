/* The words of the Accounting page.
 *
 * A posting is a BUSINESS EVENT before it is a row of debits and credits: a
 * bill was issued, cash came in, a credit note was raised. The service writes
 * its own description and stamps the identifier into it; this file decides how
 * the event is named and leaves every identifier for the technical fold.
 */

/** What made the posting, said as the event it was. */
export const SOURCES = {
  bill: 'Bill issued',
  payment: 'Payment received',
  creditNote: 'Credit note raised',
  dispute: 'Dispute credited',
  refund: 'Refund paid',
  remittance: 'Provider payout settled',
  loyalty: 'Loyalty points accrued',
  clubShare: 'Community sponsorship',
  wholesaleCogs: 'Wholesale access charged',
  mobileWholesaleCogs: 'Host network charged',
  mobileWholesaleCogsDelta: 'Host network correction',
  mobileWholesaleRevenue: 'Wholesale customer billed',
  mobileWholesaleRevenueDelta: 'Wholesale correction',
  deviceActivation: 'Handset handed over',
  deviceEtf: 'Early termination charged',
  devicePayout: 'Residual value paid',
  deviceSwap: 'Handset swapped',
  deviceUnwind: 'Handset contract unwound',
  deviceWithdrawal: 'Handset returned',
};

export const sourceLabel = (value) => SOURCES[value] || value || 'Posting';

/** Money in the ledger's own two decimals, grouped so six figures can be read. */
export function amount(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return '';
  return n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/** The two sides of one entry, so the page can say "balanced" and mean it. */
export function sides(entry) {
  let debit = 0;
  let credit = 0;
  for (const line of (entry && entry.lines) || []) {
    debit += Number(line.debit) || 0;
    credit += Number(line.credit) || 0;
  }
  return { debit, credit, balanced: Math.abs(debit - credit) < 0.005 };
}

/** The accounts one entry touched, named — never the codes alone. */
export function accountsOf(entry) {
  const seen = new Map();
  for (const line of (entry && entry.lines) || []) {
    if (!seen.has(line.accountCode)) seen.set(line.accountCode, line.accountName);
  }
  return [...seen.entries()].map(([code, name]) => ({ code, name }));
}

/** The same list as one cell: two names, then how many more. A device unwind
 *  touches five accounts, and five names in a table cell is a paragraph. */
export function accountsSaid(entry) {
  const all = accountsOf(entry).map((a) => a.name);
  if (all.length <= 2) return all.join(' · ');
  return `${all.slice(0, 2).join(' · ')} +${all.length - 2} more`;
}

/** How a ladder state is said out loud. */
export const LADDER = [
  { key: 'draft', label: 'Drafted' },
  { key: 'validated', label: 'Validated' },
  { key: 'approved', label: 'Approved' },
  { key: 'active', label: 'Live' },
];

export const stateLabel = (value) => (LADDER.find((s) => s.key === value) || {}).label
  || (value === 'withdrawn' ? 'Withdrawn' : value || '—');

/** Which rung a change stands on, so the ladder can be drawn. */
export const rungOf = (value) => LADDER.findIndex((s) => s.key === value);

/** A setting as a person writes it: 25, not 25.000000. */
export function setting(value) {
  if (value === null || value === undefined || value === '') return 'nothing';
  const n = Number(value);
  return Number.isFinite(n) ? String(n) : String(value);
}
