import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { creditNotice } from '../../api.js';
import { t } from '../../i18n.js';
import CreditExplanation from '../orders/CreditExplanation.jsx';

// #422 — the automated-decision notice, shown BEFORE a credit-checked order is placed.
// Whether this shop checks credit at all, and with whom, is ordering's answer; the
// shop only words it. Nothing is shown when the shop does not check, or when the
// notice could not be read (the order page explains any decision afterwards).
export default function CreditNotice() {
  const [notice, setNotice] = useState(null);
  useEffect(() => { creditNotice().then(setNotice).catch(() => setNotice(null)); }, []);
  if (!notice || !notice.checked) return null;
  return (
    <p className="dim small" data-testid="credit-notice">
      ℹ️ {t('Before we accept a plan you pay for after use, we check your credit with')} {t(notice.bureau)}.
      {' '}{t('The check is automated, but a decline is never final without a way to a person.')}
      {' '}{t('You can ask why, and ask a person to look again, from')} <Link to="/orders">{t('My orders')}</Link>.
    </p>
  );
}

// #422 review — the shop's own rules refused the order, so there is no order to open
// later: ordering's 422 links the credit decision it left, and the customer reads why
// and can ask a person right here. Shown only when ordering sent the link.
export function CreditRefusal({ error }) {
  return (
    <div data-testid="credit-refused">
      <p className="error">{error.message}</p>
      <CreditExplanation decisionId={error.creditDecision.id} />
    </div>
  );
}
