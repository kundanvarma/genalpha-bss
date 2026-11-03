import { useEffect, useState } from 'react';
import { askCreditDecisionReview, askCreditReview, creditDecisionById, orderCreditDecision } from '../../api.js';
import { t } from '../../i18n.js';

// #422 — what a credit check did to an order, in plain words, and a way to ask a
// person. Ordering decides everything here (whether to show it at all, the reason,
// what happened to the order, whether it can be contested, what happens next); this
// file only translates and shows. A sentence the shop does not know is shown as
// ordering wrote it. Reached by the order (My orders) or, when the operator's rule
// refused the order and it never existed, by the decision's own id (checkout).
const REASON = {
  PAYMENT_REMARK: 'The credit bureau reported a registered payment remark (an unpaid debt on record).',
  SCORE_BELOW_THRESHOLD: "The credit bureau's score band for you is below what this plan needs.",
  SCORE_NEAR_THRESHOLD: "The credit bureau's score band for you is close to what this plan needs.",
  SCORE_MEETS_THRESHOLD: "The credit bureau's score band for you meets what this plan needs.",
  CREDIT_FROZEN: 'Your credit information is frozen at the credit bureau, so it could not be assessed.',
};
const DATA = {
  identity: 'Who you are: your customer record, which the credit bureau matches to its own register.',
  scoreBand: 'The score band the credit bureau gives you.',
  paymentRemarks: 'Whether the credit bureau holds a registered payment remark (an unpaid debt) for you.',
  purpose: 'What the check is for: signing up to a plan you pay for after use.',
};
const OUTCOMES = [
  'Your order is on hold until a person reviews it.',
  'A person reviewed your order and let it go ahead.',
  'Your order was cancelled.',
  "Your order was not placed: this shop's own rules refused it.",
  'Your order was not placed.',
  'Your order went ahead.',
  "Your order went ahead; the credit bureau's answer was close to the line.",
  'The order could not be placed while your credit information is frozen.',
];
const HUMAN = "A credit decline is never final without a way to a person: a declined order waits on hold for a person to decide, and if this shop's own rules refused the order, you can still ask a person to review the decision.";
const NOT_KEPT = "We never receive or keep the credit bureau's report — only its decision, the score band and whether a payment remark exists.";
const HOW = 'Ask for a person to review the decision, and add a note if something is wrong — for example a debt that has since been paid. You can also ask the credit bureau for a copy of what it holds about you, and have it corrected.';
const NEXT = [
  'A person in customer care will look at your order and the reason, and contact you. Your order stays on hold until then — you do not need to order again.',
  'A person in customer care will look at the decision and the reason, and contact you.',
  'A person in customer care will look at the decision and the reason, and contact you. Your order was not placed, so nothing is waiting — if the decision changes, they will help you order again.',
];

// ordering's sentence, in the shop's language when the shop knows it
const said = (sentence, known) => (known.includes(sentence) ? t(sentence) : sentence);

export default function CreditExplanation({ orderId, decisionId }) {
  const [why, setWhy] = useState(null);
  const [failed, setFailed] = useState(false);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [receipt, setReceipt] = useState(null);
  const [askError, setAskError] = useState(null);

  useEffect(() => {
    (decisionId ? creditDecisionById(decisionId) : orderCreditDecision(orderId)).then(setWhy).catch(() => setFailed(true));
  }, [orderId, decisionId]);

  if (failed) return <p className="dim small" data-testid="credit-why-unavailable">{t('We could not fetch the reason for the credit check right now — try again shortly.')}</p>;
  // ordering says whether this is worth showing; an approval says nothing here
  if (!why || !why.show) return null;

  async function ask() {
    setBusy(true);
    setAskError(null);
    try {
      setReceipt(await (decisionId ? askCreditDecisionReview(decisionId, note) : askCreditReview(orderId, note)));
    } catch (e) {
      setAskError(e.message);
    } finally {
      setBusy(false);
    }
  }

  const contest = why.contest || {};
  const requested = receipt || (contest.requested ? contest : null);
  const next = requested && (requested.nextSteps ? said(requested.nextSteps, NEXT) : null);
  return (
    <div className="freezepanel small" data-testid="credit-why">
      <strong>{t('About the credit check')}</strong>
      {why.outcome && <p data-testid="credit-outcome">{said(why.outcome, OUTCOMES)}</p>}
      {why.refusal && <p data-testid="credit-refusal">{why.refusal}</p>}
      <p data-testid="credit-reason">{REASON[why.reasonCode] ? t(REASON[why.reasonCode]) : why.reason}</p>
      {why.scoreBand && <p>{t('Score band from the credit bureau')}: <b>{why.scoreBand}</b></p>}
      <p data-testid="credit-human">{said(why.humanReview, [HUMAN])}</p>
      <details>
        <summary>{t('What the credit check used')}</summary>
        <ul>
          {(why.dataUsed || []).map((d) => <li key={d.code}>{DATA[d.code] ? t(DATA[d.code]) : d.label}</li>)}
        </ul>
        {why.notKept && <p>{said(why.notKept, [NOT_KEPT])}</p>}
      </details>
      {requested ? (
        <p data-testid="credit-review-requested">✓ {t('You asked a person to review this.')} {next}</p>
      ) : contest.available && (
        <div data-testid="credit-contest">
          <p>{said(contest.how, [HOW])}</p>
          <textarea rows={2} maxLength={1000} value={note} data-testid="credit-review-note"
                    placeholder={t('Anything we should know? (optional)')}
                    onChange={(e) => setNote(e.target.value)} />
          <button className="ghost" disabled={busy} data-testid="credit-review-ask" onClick={ask}>
            {busy ? t('Sending…') : t('Ask a person to review')}
          </button>
          {askError && <p className="error">{askError}</p>}
        </div>
      )}
    </div>
  );
}
