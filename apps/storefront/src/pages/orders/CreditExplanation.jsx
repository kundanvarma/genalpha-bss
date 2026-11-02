import { useEffect, useState } from 'react';
import { askCreditReview, orderCreditDecision } from '../../api.js';
import { t } from '../../i18n.js';

// #422 — why an order waits on a credit check, in plain words, and a way to ask a
// person. Ordering decides everything here (the reason, whether it can be contested,
// what happens next); this file only translates and shows. A reason code the shop
// does not know falls back to the sentence ordering stored.
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
const HUMAN = 'A person reviews every credit decline: a decline puts the order on hold for a person to decide, it never refuses the order on its own.';
const NOT_KEPT = "We never receive or keep the credit bureau's report — only its decision, the score band and whether a payment remark exists.";
const HOW = 'Ask for a person to review the decision, and add a note if something is wrong — for example a debt that has since been paid. You can also ask the credit bureau for a copy of what it holds about you, and have it corrected.';
const NEXT_HELD = 'A person in customer care will look at your order and the reason, and contact you. Your order stays on hold until then — you do not need to order again.';
const NEXT = 'A person in customer care will look at the decision and the reason, and contact you.';

// ordering's sentence, in the shop's language when the shop knows it
const said = (sentence, known) => (known.includes(sentence) ? t(sentence) : sentence);

export default function CreditExplanation({ orderId }) {
  const [why, setWhy] = useState(null);
  const [failed, setFailed] = useState(false);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [receipt, setReceipt] = useState(null);
  const [askError, setAskError] = useState(null);

  useEffect(() => {
    orderCreditDecision(orderId).then(setWhy).catch(() => setFailed(true));
  }, [orderId]);

  if (failed) return <p className="dim small" data-testid="credit-why-unavailable">{t('We could not fetch the reason for the credit check right now — try again shortly.')}</p>;
  // an order held for another reason (a family approval, a top-up) says nothing here
  if (!why || !why.assessed || why.decision !== 'decline') return null;

  async function ask() {
    setBusy(true);
    setAskError(null);
    try {
      setReceipt(await askCreditReview(orderId, note));
    } catch (e) {
      setAskError(e.message);
    } finally {
      setBusy(false);
    }
  }

  const contest = why.contest || {};
  const requested = receipt || (contest.requested ? contest : null);
  const next = requested && (requested.nextSteps ? said(requested.nextSteps, [NEXT_HELD, NEXT]) : null);
  return (
    <div className="freezepanel small" data-testid="credit-why">
      <strong>{t('Why your order is waiting')}</strong>
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
