import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { memberProducts, myHousehold } from '../api.js';
import { t } from '../i18n.js';

/**
 * The guardian's window onto a dependent: everything the FAMILY PAYS FOR on
 * that person's life, opened from the Family hub in its own tab. Works for
 * the payer and for family admins alike — inventory verifies the household
 * link live at the party source. Usage meters, SIM and full self-care live
 * on the dependent's OWN sign-in: paying for someone is not surveillance.
 */
export default function FamilyMember() {
  const { id } = useParams();
  const [dep, setDep] = useState(null);
  const [paid, setPaid] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    myHousehold()
      .then((hh) => {
        // my dependents as the payer, or the whole family as an admin
        const found = [...(hh.dependents || []), ...(hh.family || [])].find((d) => d.id === id);
        if (!found) throw new Error(t('not one of your household dependents'));
        setDep(found);
      })
      .catch((e) => setError(e.message));
    memberProducts(id)
      .then(setPaid)
      .catch((e) => setError(e.message));
  }, [id]);

  if (error) return <p className="error">{error}</p>;
  if (!dep || !paid) return <p className="dim">{t('Loading…')}</p>;

  return (
    <div className="detail" data-testid="family-member-page">
      <h1>👪 {dep.givenName} {dep.familyName}</h1>
      <p className="dim">{t('What you pay for on their behalf — it bills to you, attributed to them.')}</p>
      {paid.map((p) => (
        <div className="row" key={p.id}>
          <strong>{p.name}</strong>
          <span className={`state ${p.status}`}>{p.status}</span>
        </div>
      ))}
      {!paid.length && <p className="dim">{t('Nothing yet — order for them from your Family page.')}</p>}
      <p className="dim" style={{ fontSize: 13, marginTop: 16 }}>
        {t('Usage, SIM care and everything else live on their own sign-in — paying for someone is not watching them.')}
      </p>
    </div>
  );
}
