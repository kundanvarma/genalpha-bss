import { useState } from 'react';
import { categoryNamed, categoryRef, createOffering } from './deskApi.js';

/* A new benefit, with no JSON (#359): the fields a product manager knows —
 * name, group, cost in Choices, the partner and their terms, what the partner
 * charges a month, and an optional hold (a subsidised device offer). It is
 * filed in "Partner services" (so the orchestrator activates it with the
 * partner), "Choices" (so the shelves find it) and its own group. */
const GROUPS = ['Streaming', 'Audio', 'AI', 'Network extras', 'Devices'];
const EMPTY = { name: '', description: '', group: 'Streaming', cost: '1', partner: '', terms: '', wholesale: '', hold: '' };

export function BenefitForm({ authFetch, onCreated }) {
  const [f, setF] = useState(EMPTY);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const set = (k) => (e) => setF((x) => ({ ...x, [k]: e.target.value }));

  async function create(e) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const cats = await Promise.all(['Partner services', 'Choices', f.group].map((n) => categoryNamed(authFetch, n)));
      const made = await createOffering(authFetch, {
        name: f.name.trim(),
        description: f.description.trim() || undefined,
        isBundle: false,
        category: cats.map(categoryRef),
        choiceCost: Number(f.cost),
        choicePartner: f.partner.trim() || undefined,
        choicePartnerTerms: f.terms.trim() || undefined,
        choiceWholesaleCost: f.wholesale === '' ? undefined : Number(f.wholesale),
        choiceHoldMonths: f.hold === '' || Number(f.hold) === 0 ? undefined : Number(f.hold),
      });
      setF(EMPTY);
      onCreated(made);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  const field = (label, k, props = {}) => (
    <label style={{ display: 'grid', gap: 2 }}>
      <span className="small">{label}</span>
      <input name={k} value={f[k]} onChange={set(k)} disabled={busy} {...props} />
    </label>
  );
  return (
    <form data-testid="benefit-new" onSubmit={create} className="card" style={{ display: 'grid', gap: 8, gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))' }}>
      {field('Name', 'name', { required: true, placeholder: 'e.g. StreamCo Premium' })}
      <label style={{ display: 'grid', gap: 2 }}>
        <span className="small">Group</span>
        <select name="group" value={f.group} onChange={set('group')} disabled={busy}>
          {GROUPS.map((g) => <option key={g}>{g}</option>)}
        </select>
      </label>
      {field('Costs (Choices)', 'cost', { type: 'number', min: 1, step: 1, required: true })}
      {field('Partner', 'partner', { placeholder: 'who provides it' })}
      {field("Partner's terms", 'terms', { placeholder: 'a link to their terms' })}
      {field('Partner charges a month', 'wholesale', { type: 'number', min: 0, step: '0.01', placeholder: 'what we pay them' })}
      {field('Holds its Choice (months)', 'hold', { type: 'number', min: 0, step: 1, placeholder: '0 — no hold' })}
      {field('What the customer reads', 'description', { placeholder: 'one sentence for the shop' })}
      <div style={{ alignSelf: 'end' }}>
        <button className="primary" type="submit" disabled={busy || !f.name.trim()}>Create benefit</button>
      </div>
      {error && <p className="error small" role="alert" style={{ gridColumn: '1 / -1' }}>{error}</p>}
    </form>
  );
}
