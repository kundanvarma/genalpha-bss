import { useState } from 'react';
import { hasRole } from '../../auth.js';
import { logInteraction } from '../../api.js';

/* CSR-UX-005 (#149): an action inside a row applies to that row.
 *
 * "Upgrade options" used to be rendered on BOTH branches of a service row — on
 * the running service AND on a commercial product with nothing running under
 * it. An agent reads a button inside a row as being about that row, so the
 * button promised a journey the object could not take.
 *
 * The rule, split three ways:
 *   · a row with a running service under an active product gets the journey
 *     scoped to that object, named for what it is — "Change plan" on a mobile
 *     line, "Change package" on TV;
 *   · a product row with nothing running gets what does apply to a commercial
 *     product: its own detail, and nothing that pretends a service exists;
 *   · the GENERIC journey — "the customer wants something more" with no object
 *     chosen yet — moves up to the Services header, where the object is the
 *     customer.
 *
 * The answer follows the same rule as the diagnosis report (#142): the options
 * card renders under the row whose button opened it.
 */

const party = (id) => [{ id, role: 'customer', '@referredType': 'Individual' }];

/* What the journey is called on each kind of line. The words are the ones a
 * customer uses on the phone; the kind itself is still guessed from the name
 * upstream (#143 — not this ticket's business). */
const PLAN_WORDS = { tv: 'Change package', mobile: 'Change plan', broadband: 'Change plan', voice: 'Change plan', other: 'Change plan' };

/** Does the plan-change journey apply to this row at all? Product AND service, both live. */
export const canChangePlan = (product, service) => Boolean(
  product && service && product.status === 'active' && service.state !== 'ceased' && hasRole('ordering:write'),
);

/** The journey scoped to ONE object, named for what that object is. */
export function ChangePlanButton({ product, service, kind, onOptions }) {
  if (!canChangePlan(product, service)) return null;
  const word = PLAN_WORDS[kind] || PLAN_WORDS.other;
  return (
    <button className="ghost" data-testid={`csr-upgrade-options-${product.id}`}
      title={`What ${product.name} could become — from the operational ontology, with every condition checked before anything changes`}
      onClick={() => onOptions(product)}>
      {word}
    </button>
  );
}

/** A commercial product with nothing running under it: its own facts, and no service journey. */
export function ProductActions({ product }) {
  const [open, setOpen] = useState(false);
  const chars = product.productCharacteristic || [];
  const price = (product.productPrice || [])[0];
  return (
    <div className="rowend">
      <span className={`state ${product.status}`}>{product.status}</span>
      <button className="ghost" data-testid={`csr-view-product-${product.id}`}
        title="What the customer bought — there is no running service to change here"
        aria-expanded={open} onClick={() => setOpen((o) => !o)}>View product</button>
      {open && (
        <div className="prod-detail" data-testid={`csr-product-detail-${product.id}`}>
          <p className="dim small">Bought {product.startDate ? new Date(product.startDate).toLocaleDateString() : 'on an unrecorded date'}
            {price ? ` · ${price.name || 'price'} ${price.price?.taxIncludedAmount?.value ?? ''} ${price.price?.taxIncludedAmount?.unit || ''}` : ''}</p>
          {chars.slice(0, 6).map((c) => <p className="dim small" key={c.name}>{c.name}: {String(c.value)}</p>)}
          {!chars.length && <p className="dim small">Nothing recorded on it beyond its name and state.</p>}
        </div>
      )}
    </div>
  );
}

/* The running service only earns a mention when it says something the product
 * name does not — its number, or a different name. Repeating "Kids Smartwatch
 * Kids Smartwatch" tells an agent nothing and makes the list harder to scan. */
const svLabel = (p, sv) => {
  const number = (sv.supportingResource || []).map((r) => r.value).find(Boolean);
  if (number) return number;
  return (sv.name || '').trim() === (p.name || '').trim() ? null : sv.name;
};

/** The generic journey, at the level it actually belongs to: the customer. */
export function AddOrUpgrade({ rows, onOptions, onAdd }) {
  const changeable = rows.filter(({ product: p, service: sv }) => canChangePlan(p, sv));
  if (!hasRole('ordering:write')) return null;
  return (
    <details className="add-upgrade" data-testid="add-or-upgrade">
      <summary className="ghost">Add or upgrade services</summary>
      <div className="rows">
        <p className="dim small">Pick what should change. Nothing happens until the conditions are checked and you click through.</p>
        {changeable.map(({ product: p, service: sv }) => (
          <div className="row" key={p.id}>
            <span>{p.name}{svLabel(p, sv) && <span className="dim small"> {svLabel(p, sv)}</span>}</span>
            <button className="ghost" data-testid={`csr-upgrade-pick-${p.id}`} onClick={() => onOptions(p)}>See options</button>
          </div>
        ))}
        {!changeable.length && <p className="dim small">Nothing running that has a dearer plan to move to.</p>}
        <div className="row">
          <span>Something the customer does not have yet</span>
          <button className="ghost" data-testid="csr-add-service" onClick={onAdd}>Order something new</button>
        </div>
      </div>
    </details>
  );
}

/** The ontology's answer about ONE product: what it could become, and every condition checked. */
export function UpgradeCard({ upgrade, setUpgrade, bss, act, id }) {
  if (!upgrade) return null;
  return (
    <div className="rows" data-testid="upgrade-card">
      <p className="dim small">{upgrade.name}: {upgrade.options.length ? 'could become' : 'has no dearer plan in its family on this channel.'}</p>
      {upgrade.options.map((o) => (
        <div className="row" key={o.id}>
          <span>{o.name} <span className="dim small">{o.monthly}/month</span></span>
          <div className="rowend">
            <button className="ghost" data-testid={`csr-upgrade-check-${o.id}`}
              onClick={() => act(async () => {
                const c = await bss.checkUpgradeSubscription({ subscriptionId: upgrade.productId, targetOfferingId: o.id });
                setUpgrade((u) => ({ ...u, verdicts: { target: o, ...c }, said: null }));
              }, 'services')}>Check</button>
            {upgrade.verdicts?.target?.id === o.id && upgrade.verdicts.allowed && (
              <button className="primary" data-testid={`csr-upgrade-do-${o.id}`}
                onClick={() => act(async () => {
                  const done = await bss.upgradeSubscription({ subscriptionId: upgrade.productId, targetOfferingId: o.id });
                  setUpgrade((u) => ({ ...u, said: done.said, verdicts: null }));
                  await logInteraction({ description: `Plan upgraded through the ontology: ${done.said}`, channel: 'phone', direction: 'outbound', sourceSystem: 'csr-console', relatedParty: party(id) });
                }, 'services')}>Upgrade</button>
            )}
          </div>
        </div>
      ))}
      {upgrade.verdicts && (
        <div data-testid="upgrade-verdicts">
          <p className={upgrade.verdicts.allowed ? 'dim small' : 'error'}>
            {upgrade.verdicts.allowed ? `${upgrade.verdicts.target.name}: this could happen.` : `Refused: ${upgrade.verdicts.refusal}`}
          </p>
          {upgrade.verdicts.preconditions.map((v) => (
            <p key={v.id} className="dim small">{v.verdict === 'holds' ? '✓' : v.verdict === 'fails' ? '✗' : '?'} {v.says}{v.detail ? ` — ${v.detail}` : ''}</p>
          ))}
          <p className="dim small">Permission: {upgrade.verdicts.permission?.says}. Policy: {upgrade.verdicts.policy?.says}.</p>
        </div>
      )}
      {upgrade.said && <p data-testid="upgrade-said">{upgrade.said}</p>}
    </div>
  );
}
