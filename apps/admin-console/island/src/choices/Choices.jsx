import { useCallback, useEffect, useState } from 'react';
import { allOfferings, patchOffering } from './deskApi.js';
import { BenefitForm } from './BenefitForm.jsx';

/* BENEFIT CHOICES, AUTHORED (#359).
 *
 * A plan grants Choices; a household spends them on benefits. Until now both
 * were seed data. Here a product manager sets what each plan grants and makes
 * a benefit — with no JSON. Two things the page says plainly:
 *  - a plan's grant is per LINE: each line bought on the plan adds its own
 *    Choices to the household (there is no separate "extra line" rule);
 *  - a new benefit is an ordinary offering: where launch governance is on it
 *    waits for an approver on the Approvals desk, like any other product.
 */
const isPlan = (o) => !o.isBundle && (o.category || []).some((c) => c.name === 'Mobile plans');

export function Choices({ authFetch }) {
  const [offerings, setOfferings] = useState(null);
  const [error, setError] = useState(null);
  const [said, setSaid] = useState(null);
  const [grants, setGrants] = useState({});
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => allOfferings(authFetch)
    .then((all) => { setOfferings(all); setGrants({}); })
    .catch((e) => setError(`Could not read the catalog: ${e.message}`)), [authFetch]);
  useEffect(() => { load(); }, [load]);

  async function saveGrant(plan) {
    setBusy(true);
    setError(null);
    try {
      await patchOffering(authFetch, plan.id, { choicesGranted: Number(grants[plan.id]) });
      setSaid(`${plan.name} now grants ${grants[plan.id]} Choice${Number(grants[plan.id]) === 1 ? '' : 's'} per line.`);
      await load();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  if (!offerings) return <div data-testid="island-choices"><p className="dim">{error || 'Loading the catalog…'}</p></div>;
  const plans = offerings.filter((o) => isPlan(o) && o.lifecycleStatus !== 'Retired');
  const benefits = offerings.filter((o) => o.choiceCost > 0);
  return (
    <div data-testid="island-choices" style={{ display: 'grid', gap: 16 }}>
      <p className="zone-note">A plan grants Choices for each line bought on it; a household spends them on benefits. Set the grants, make a benefit — no JSON.</p>
      {said && <p className="ok small" data-testid="choices-said">{said}</p>}
      {error && <p className="error small" role="alert">{error}</p>}

      <section className="card">
        <h3>What each plan grants</h3>
        <table data-testid="choice-plans"><thead><tr><th>Plan</th><th>Choices per line</th><th /></tr></thead>
          <tbody>{plans.map((p) => (
            <tr key={p.id} data-plan={p.name}>
              <td>{p.name}</td>
              <td><input type="number" min={1} step={1} aria-label={`Choices ${p.name} grants per line`} disabled={busy}
                value={grants[p.id] ?? (p.choicesGranted || '')} placeholder="none"
                onChange={(e) => setGrants((g) => ({ ...g, [p.id]: e.target.value }))} /></td>
              <td>{grants[p.id] !== undefined && Number(grants[p.id]) >= 1 && (
                <button className="ghost small" disabled={busy} onClick={() => saveGrant(p)}>Save</button>)}</td>
            </tr>
          ))}</tbody>
        </table>
      </section>

      <section className="card">
        <h3>Benefits <span className="dim small">{benefits.length}</span></h3>
        <table data-testid="choice-benefits"><thead><tr><th>Benefit</th><th>Costs</th><th>Partner</th><th>Partner charges / month</th><th>Holds</th><th>State</th></tr></thead>
          <tbody>{benefits.map((b) => (
            <tr key={b.id} data-benefit={b.name}>
              <td>{b.name}</td>
              <td>{b.choiceCost} Choice{b.choiceCost === 1 ? '' : 's'}</td>
              <td>{b.choicePartner || '—'}{b.choicePartnerTerms && <> · <a href={b.choicePartnerTerms} target="_blank" rel="noreferrer">terms</a></>}</td>
              <td>{b.choiceWholesaleCost == null ? '—' : Number(b.choiceWholesaleCost).toFixed(2)}</td>
              <td>{b.choiceHoldMonths ? `${b.choiceHoldMonths} months` : '—'}</td>
              <td data-testid="benefit-state">{b.lifecycleStatus === 'In design'
                ? <button className="linkish small" onClick={() => window.consoleGoTo?.('approvals')}>waiting for launch approval →</button>
                : b.lifecycleStatus}</td>
            </tr>
          ))}</tbody>
        </table>
      </section>

      <section>
        <h3>New benefit</h3>
        <BenefitForm authFetch={authFetch} onCreated={(made) => {
          setSaid(made.lifecycleStatus === 'In design'
            ? `${made.name} is made and waits for launch approval on the Approvals desk.`
            : `${made.name} is made and on sale — households can pick it now.`);
          load();
        }} />
      </section>
    </div>
  );
}
