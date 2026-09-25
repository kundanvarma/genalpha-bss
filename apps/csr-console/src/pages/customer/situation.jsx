/* What a bill says about itself, and the words the desk uses for it.
 *
 * Billing computes the situation once — from the bill's state, what has been
 * allocated against it, a standing payment arrangement and any open dispute —
 * and every channel reads that one answer. The desk never works lateness out
 * for itself, so an agent on the phone and the customer looking at the app can
 * never disagree about whether a bill is overdue.
 */
export const OPEN_BILL_STATES = ['new', 'validated', 'sent', 'partiallyPaid'];
export const due = (b) => Number(b.amountDue?.value ?? b.amountDue ?? 0);

export const situationOf = (b) => (b.billSituation || {}).value || null;
export const reasonOf = (b) => (b.billSituation || {}).reason || '';
export const arrangedUntil = (b) => (b.billSituation || {}).arrangementUntil || null;

export const SITUATION_WORDS = {
  issued: 'issued', outstanding: 'outstanding', partiallyPaid: 'part paid', paid: 'paid',
  overdue: 'overdue', arrangement: 'arrangement', disputed: 'disputed', writtenOff: 'written off',
};

/* Still owing is what an agent means by "open": paid, written off and a bill
 * with nothing yet to collect are not the caller's problem. A bill raised
 * before situations existed falls back to its stored state. */
export const STILL_OWING = ['outstanding', 'partiallyPaid', 'overdue', 'arrangement', 'disputed'];
export const stillOwing = (b) => (situationOf(b)
  ? STILL_OWING.includes(situationOf(b))
  : OPEN_BILL_STATES.includes(b.state) && due(b) > 0);
