/* What a bill says about itself, in the customer's words.
 *
 * Billing works the situation out once — from the bill, what has been paid
 * against it, an arrangement the operator granted and any open dispute — and
 * every channel reads that one answer. So the shop, an agent on the phone and
 * the back office can never drift apart about whether a bill is late. Warm,
 * plain, and never the word "overdue" when the operator has granted more time.
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

/** The chip on a bill row: the situation, with the reason behind it on hover. */
export function Situation({ bill }) {
  return (
    <span className={`state ${bill.billSituation?.value || bill.state}`}
          data-testid="bill-situation" title={bill.billSituation?.reason || ''}>
      {situationWords(bill)}
    </span>
  );
}

/* The consequences line the law wants said plainly, per ladder rung. */
export const CASE_CONSEQUENCE = {
  reminded: 'A payment reminder has been sent (the statutory reminder fee rides the bill). '
    + 'If the balance stays unpaid, a payment demand follows and services can later be restricted.',
  warned: 'A payment demand has been sent. Unless the balance is paid, outgoing services can be '
    + 'restricted at the earliest one month after the demand — emergency numbers always stay reachable.',
  restricted: 'Outgoing services are restricted — emergency numbers still work. '
    + 'Unless the balance is paid, the line will be suspended next.',
  suspended: 'Your line is suspended for non-payment. No subscription charges accrue while it is '
    + 'suspended — paying restores your services at once.',
  terminated: 'This subscription was terminated for non-payment. Please contact us to settle the remaining balance.',
  writtenOff: 'This balance has been closed. Please contact us if you believe this is wrong.',
};
