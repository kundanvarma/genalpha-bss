/* What a bill says about itself, in the customer's words.
 *
 * Billing works the situation out once — from the bill, what has been paid
 * against it, an arrangement the operator granted and any open dispute — and
 * every channel reads that one answer, so the app, the shop and an agent on
 * the phone can never disagree about whether a bill is late. The app never
 * says "overdue" when the operator has granted more time.
 */
const WORDS = {
  issued: () => 'issued',
  outstanding: (s) => (s.currentDueDate ? `due ${s.currentDueDate}` : 'due'),
  partiallyPaid: (s) => (s.currentDueDate ? `part paid · rest due ${s.currentDueDate}` : 'part paid'),
  paid: () => 'paid',
  overdue: () => 'overdue',
  arrangement: (s) => (s.arrangementUntil ? `arrangement until ${s.arrangementUntil}` : 'arrangement in place'),
  disputed: () => 'in dispute',
  writtenOff: () => 'written off',
};

export function situationWords(bill) {
  const s = bill.billSituation;
  if (!s || !WORDS[s.value]) return bill.state;
  return WORDS[s.value](s);
}

/** Green when there is nothing to do, red only when the customer is genuinely late. */
export function situationTone(bill, palette) {
  const value = bill.billSituation?.value || (bill.state === 'settled' ? 'paid' : null);
  if (value === 'paid' || value === 'writtenOff' || value === 'arrangement') return palette.ok;
  if (value === 'overdue') return palette.err;
  return palette.ink;
}

/** Still payable: anything the customer can settle now. */
export function stillPayable(bill) {
  const value = bill.billSituation?.value;
  if (!value) return bill.state !== 'settled';
  return ['outstanding', 'partiallyPaid', 'overdue', 'arrangement'].includes(value);
}
