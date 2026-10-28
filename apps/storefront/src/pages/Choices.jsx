import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { choicePreview, listOfferings, myChoices, myProducts, pickBenefit, releaseBenefit, swapBenefit } from '../api.js';
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
  const [products, setProducts] = useState([]);
  const [plans, setPlans] = useState([]);
  const [busy, setBusy] = useState(null);
  const [notice, setNotice] = useState(null);
  const [error, setError] = useState(null);
  const [previews, setPreviews] = useState({}); // offeringId -> ordering's "can I pick this?" (#392)

  function refresh() {
    myProducts().then(setProducts).catch((e) => setError(e.message));
    return myChoices().then(setPool).catch((e) => setError(e.message));
  }
  // the product a benefit lives in: the one the picking order made (its TMF637 lineage)
  const productOf = (a) => products.find((p) => p.status === 'active'
    && (p.productOrderItem || []).some((l) => l.productOrderId === a.orderId));
  useEffect(() => {
    refresh();
    listOfferings().then((all) => {
      setBenefits(all.filter((o) => o.choiceCost > 0));
      setPlans(all.filter((o) => o.choicesGranted > 0));
    }).catch((e) => setError(`${t('The benefits could not be loaded')}: ${e.message}`));
  }, []);
  // ask ordering, per benefit, whether it can be picked and why not — the page decides nothing (#392);
  // an unanswered preview leaves the button to the order itself, which is the authority
  useEffect(() => {
    if (!pool?.offered || !benefits?.length) return;
    Promise.all(benefits.map((b) => choicePreview(b.id).then((p) => [b.id, p]).catch(() => [b.id, null])))
      .then((pairs) => setPreviews(Object.fromEntries(pairs)));
  }, [pool, benefits]);
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

  async function act(id, run, done) {
    setBusy(id);
    setNotice(null);
    try {
      await run();
      setNotice({ ok: true, text: done });
      await refresh();
    } catch (e) {
      setNotice({ ok: false, text: e.message });
    } finally {
      setBusy(null);
    }
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
        {pool.swapsThisQuarter > 0 && (
          <p className="muted" data-testid="choices-swaps" style={{ margin: '6px 0 0' }}>
            {t('Swaps this quarter')}: {pool.swapsThisQuarter} · {t('a new quarter starts')} {pool.nextQuarterStarts}
          </p>
        )}
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
            <div key={a.id} className="row" data-testid="choice-row" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8, padding: '4px 0', flexWrap: 'wrap' }}>
              <span style={{ flex: 1 }}>{a.offeringName}</span>
              <span className="muted" data-testid="choice-state">{a.state === 'held' ? t('Activating…') : t('Active')}</span>
              {a.heldUntil && <span className="muted" data-testid="held-until">{t('Held until')} {a.heldUntil}</span>}
              {a.state === 'committed' && !a.heldUntil && productOf(a) && (
                <>
                  <select data-testid="swap-for" aria-label={`${t('Swap')} ${a.offeringName}`} disabled={busy !== null} value=""
                    onChange={(e) => { const to = (benefits || []).find((b) => b.id === e.target.value); if (to) act(a.id, () => swapBenefit(productOf(a).id, to), `${t('Swapping')} ${a.offeringName} → ${to.name}. ${a.offeringName} ${t('stays active until')} ${to.name} ${t('is.')}`); }}>
                    <option value="">{t('Swap for…')}</option>
                    {(benefits || []).filter((b) => !picked.has(b.id)).map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
                  </select>
                  <button className="ghost" data-testid="release-benefit" disabled={busy !== null}
                    onClick={() => act(a.id, () => releaseBenefit(productOf(a).id), `${a.offeringName} ${t('released — pick something else whenever you like.')}`)}>
                    {t('Release')}
                  </button>
                </>
              )}
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
            const answer = previews[b.id];
            const asking = !(b.id in previews);   // until ordering answers, the page vouches for nothing
            const refused = answer && !answer.allowed;
            return (
              <div key={b.id} className="card" data-testid="benefit-card" style={{ padding: 12 }}>
                <b>{b.name}</b>
                <p className="muted" style={{ minHeight: '3em' }}>{b.description}</p>
                <p style={{ margin: '4px 0' }}>{t('Costs')} {b.choiceCost} {t('of your')} {label}</p>
                <button className="primary" data-testid="pick-benefit" disabled={mine || asking || refused || busy !== null}
                  onClick={() => pick(b)}>
                  {mine ? t('Picked') : busy === b.id ? t('Picking…') : t('Pick')}
                </button>
                {refused && !mine && <p className="muted small" data-testid="pick-reason" style={{ margin: '6px 0 0' }}>{answer.reason}</p>}
              </div>
            );
          })}
        </div>
      </section>
    </div>
  );
}
