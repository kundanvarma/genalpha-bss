import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { listOfferings, myChoices, pickBenefit } from '../api.js';
import { t } from '../i18n.js';

/*
 * The household's Choices (#354): what the plans give, what is in use, what is
 * left, and the benefits a Choice can pick. The page only asks — the ledger
 * counts and ordering holds; a pick is an ordinary order that may be refused.
 * The word for a Choice is the tenant's own (pool.label).
 */
export default function Choices() {
  const [pool, setPool] = useState(null);
  const [benefits, setBenefits] = useState(null); // null while the shelf loads — never shown as empty
  const [plans, setPlans] = useState([]);
  const [busy, setBusy] = useState(null);
  const [notice, setNotice] = useState(null);
  const [error, setError] = useState(null);

  function refresh() {
    return myChoices().then(setPool).catch((e) => setError(e.message));
  }
  useEffect(() => {
    refresh();
    listOfferings().then((all) => {
      setBenefits(all.filter((o) => o.choiceCost > 0));
      setPlans(all.filter((o) => o.choicesGranted > 0));
    }).catch((e) => setError(`${t('The benefits could not be loaded')}: ${e.message}`));
  }, []);
  // while a pick is activating, look again until the partner has confirmed it
  const activating = pool?.allocations?.some((a) => a.state === 'held');
  useEffect(() => {
    if (!activating) return undefined;
    const timer = setTimeout(refresh, 2500);
    return () => clearTimeout(timer);
  }, [pool]);

  if (error) return <div className="card error">{error}</div>;
  if (!pool) return <p>{t('Loading…')}</p>;
  const label = pool.label || 'Choices';
  if (!pool.offered) {
    return <section className="card" data-testid="choices-not-offered"><h1>{label}</h1><p>{t('Your plans do not include')} {label}.</p></section>;
  }

  async function pick(offering) {
    setBusy(offering.id);
    setNotice(null);
    try {
      await pickBenefit(offering);
      setNotice({ ok: true, text: `${offering.name} — ${t('activating now. It appears below in a moment.')}` });
      await refresh();
    } catch (e) {
      setNotice({ ok: false, text: e.message });
    } finally {
      setBusy(null);
    }
  }

  const picked = new Set((pool.allocations || []).map((a) => a.offeringId));
  return (
    <div data-testid="choices-page">
      <h1>{label}</h1>
      <section className="card" data-testid="choices-summary" style={{ padding: '14px 18px', marginBottom: 14 }}>
        <p style={{ fontSize: '1.1em', margin: 0 }}>
          <b data-testid="choices-left">{pool.left}</b> {t('of')} <b data-testid="choices-granted">{pool.granted}</b> {label} {t('left')}
          {' · '}<span data-testid="choices-in-use">{pool.inUse}</span> {t('in use')}
        </p>
        {pool.grants?.length > 0 && (
          <p className="muted" style={{ margin: '6px 0 0' }}>
            {t('From')} {pool.grants.map((g) => `${g.offeringName} (${g.choices})`).join(', ')}
          </p>
        )}
        {pool.granted === 0 && (
          <p data-testid="choices-none" style={{ margin: '6px 0 0' }}>
            {t('Your plan does not include')} {label} {t('yet. Plans that do:')}{' '}
            {plans.map((p, i) => <span key={p.id}>{i > 0 && ', '}<Link to={`/offering/${p.id}`}>{p.name}</Link> ({p.choicesGranted})</span>)}
          </p>
        )}
      </section>

      {notice && <div className={`card ${notice.ok ? 'ok' : 'error'}`} data-testid="choices-notice" role="status">{notice.text}</div>}

      {pool.allocations?.length > 0 && (
        <section className="card" data-testid="choices-in-use-list" style={{ padding: '14px 18px', marginBottom: 14 }}>
          <h2 style={{ marginTop: 0 }}>{t('In use')}</h2>
          {pool.allocations.map((a) => (
            <div key={a.id} className="row" data-testid="choice-row" style={{ display: 'flex', justifyContent: 'space-between', padding: '4px 0' }}>
              <span>{a.offeringName}</span>
              <span className="muted" data-testid="choice-state">{a.state === 'held' ? t('Activating…') : t('Active')}</span>
            </div>
          ))}
        </section>
      )}

      <section className="card" style={{ padding: '14px 18px' }}>
        <h2 style={{ marginTop: 0 }}>{t('Pick with')} {label}</h2>
        {benefits === null && <p className="muted" data-testid="benefits-loading">{t('Loading…')}</p>}
        {benefits?.length === 0 && <p className="muted">{t('No benefits are on offer right now.')}</p>}
        <div style={{ display: 'grid', gap: 12, gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))' }}>
          {(benefits || []).map((b) => {
            const mine = picked.has(b.id);
            const short = pool.left < b.choiceCost;
            return (
              <div key={b.id} className="card" data-testid="benefit-card" style={{ padding: 12 }}>
                <b>{b.name}</b>
                <p className="muted" style={{ minHeight: '3em' }}>{b.description}</p>
                <p style={{ margin: '4px 0' }}>{t('Costs')} {b.choiceCost} {t('of your')} {label}</p>
                <button className="primary" data-testid="pick-benefit" disabled={mine || short || busy !== null}
                  title={mine ? t('Already picked') : short ? `${t('Not enough')} ${label} ${t('left')}` : ''}
                  onClick={() => pick(b)}>
                  {mine ? t('Picked') : busy === b.id ? t('Picking…') : t('Pick')}
                </button>
              </div>
            );
          })}
        </div>
      </section>
    </div>
  );
}
