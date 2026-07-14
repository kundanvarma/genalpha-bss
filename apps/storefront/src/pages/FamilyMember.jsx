import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { myHousehold, myProducts } from '../api.js';
import { t } from '../i18n.js';

/**
 * The guardian's window onto a dependent: everything the payer PAYS FOR on
 * that person's life, opened from My page in its own tab. Built strictly
 * from payer-legitimate data — the products carrying the payer's own stamp
 * and the names the consented household link discloses. Usage meters, SIM
 * and full self-care live on the dependent's OWN sign-in: paying for
 * someone is not surveillance of them.
 */
export default function FamilyMember() {
  const { id } = useParams();
  const [dep, setDep] = useState(null);
  const [paid, setPaid] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    myHousehold()
      .then((hh) => {
        const found = (hh.dependents || []).find((d) => d.id === id);
        if (!found) throw new Error(t('not one of your household dependents'));
        setDep(found);
      })
      .catch((e) => setError(e.message));
    myProducts()
      .then((all) => setPaid(all.filter((p) =>
        (p.relatedParty || []).find((x) => x.role === 'customer')?.id === id)))
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
      {!paid.length && <p className="dim">{t('Nothing yet — order for them from your My page.')}</p>}
      <p className="dim" style={{ fontSize: 13, marginTop: 16 }}>
        {t('Usage, SIM care and everything else live on their own sign-in — paying for someone is not watching them.')}
      </p>
    </div>
  );
}
