import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { t } from '../../i18n.js';
import { myChoices } from '../../api.js';

/* One quiet line on Home when the household has Choices it has not spent
 * (#361, Benefit Choices): how many are left, in the operator's own word for
 * a Choice, and the way to spend them. Nothing when none are left, when this
 * operator does not offer Choices, or when the ledger cannot be read — an
 * unread pool is not an empty one, and Home does not guess. */
export default function ChoicesLine() {
  const [pool, setPool] = useState(null);
  useEffect(() => {
    myChoices().then(setPool).catch((e) => console.warn('Choices could not be read; Home leaves the line out:', e.message));
  }, []);
  if (!pool?.offered || !(pool.left > 0)) return null;
  const label = pool.label || 'Choices';
  return (
    <div className="callout info compact" data-testid="attention-choices">
      <span>{t('You have')} {pool.left} {t('of your')} {label} {t('left')} — {t('pick a benefit with them, at no extra cost.')}</span>
      <Link className="ghost linkbtn" to="/choices">{t('Pick')}</Link>
    </div>
  );
}
