import { useEffect, useState } from 'react';
import { authFetch, hasRole } from '../../auth.js';
import { logInteraction } from '../../api.js';

/*
 * The household's Choices on the care desk (#358, Benefit Choices): what the
 * plans give, what is in use, what is left — in words, never ids. Picking and
 * swapping go through the GOVERNED actions (pickBenefit, swapBenefit), so
 * every one leaves a receipt; a release is an ordinary TMF622 ending. A
 * goodwill Choice is grantGoodwillChoice: someone holding billing:admin
 * decides it on the spot, anyone else's request is filed for that approver.
 * The desk only asks — the ledger counts and ordering decides.
 */
const CHOICES = '/benefitAllowance/v1';
const ORDERS = '/tmf-api/productOrderingManagement/v4/productOrder';

async function json(res) {
  if (!res.ok) {
    // an unreadable error body still says what happened: the status
    const body = await res.json().catch(() => ({ message: `the component answered ${res.status}` }));
    throw new Error(body.message || `HTTP ${res.status}`);
  }
  return res.json();
}
const poolOf = (id) => authFetch(`${CHOICES}/pool?ownerPartyId=${encodeURIComponent(id)}`).then(json);
const productsOf = (id) => authFetch(`/tmf-api/productInventory/v4/product?relatedPartyId=${encodeURIComponent(id)}&status=active&limit=100`).then(json);
// the whole shelf, page by page: the API caps a page at 100 and the catalog is larger,
// so "the first page" silently loses benefits (the storefront's #180 lesson)
async function benefits() {
  const found = [];
  let total = Infinity;
  for (let offset = 0; offset < total; offset += 100) {
    const res = await authFetch(`/tmf-api/productCatalogManagement/v4/productOffering?limit=100&offset=${offset}&lifecycleStatus=Active`);
    const page = await json(res);
    found.push(...page.filter((o) => o.choiceCost > 0));
    const counted = Number(res.headers.get('X-Total-Count'));
    total = Number.isFinite(counted) && counted >= 0 ? counted : (page.length < 100 ? 0 : Infinity);
  }
  return found;
}

export default function Choices({ id, bss }) {
  const [pool, setPool] = useState(null);
  const [shelf, setShelf] = useState([]);
  const [products, setProducts] = useState([]);
  const [said, setSaid] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [reason, setReason] = useState('');

  function refresh() {
    setError(null);
    poolOf(id).then(setPool).catch((e) => setError(`Could not reach the Choices ledger, so this is unknown — not empty (${e.message})`));
    productsOf(id).then(setProducts).catch((e) => setError(`Could not read the customer's products (${e.message})`));
  }
  useEffect(() => {
    refresh();
    benefits().then(setShelf).catch((e) => setError(`Could not read the benefits on offer (${e.message})`));
  }, [id]);

  // the product a benefit lives in: the one its picking order made (TMF637 lineage)
  const productOf = (a) => products.find((p) => (p.productOrderItem || []).some((l) => l.productOrderId === a.orderId));

  async function run(what, fn) {
    setBusy(true);
    setSaid(null);
    setError(null);
    try {
      const out = await fn();
      // the desk's own words, never the receipt's ids: a filing says who decides, a done action says so
      const text = out?.filed ? `${what} — filed for the goodwill approver, who decides it on the approval desk`
        : out?.decisionId ? `${what} — done, with a receipt` : what;
      setSaid(text);
      logInteraction({ description: `Choices: ${text}`, channel: 'phone', direction: 'outbound', sourceSystem: 'csr-console',
        relatedParty: [{ id, role: 'customer', '@referredType': 'Individual' }] })
        .catch((e) => console.warn('interaction not logged:', e.message));
      refresh();
    } catch (e) {
      setError(e.check?.refusal || e.message);
    } finally {
      setBusy(false);
    }
  }

  if (!pool && !error) return null;
  if (pool && !pool.offered) return null;   // this operator does not offer Choices: nothing to show
  const label = pool?.label || 'Choices';
  const picked = new Set((pool?.allocations || []).map((a) => a.offeringId));
  const canAct = hasRole('ordering:write');

  return (
    <section className="card" data-testid="csr-choices" style={{ marginTop: 12 }}>
      <h3>{label} {pool && <span className="dim small">{pool.left} of {pool.granted} left · {pool.inUse} in use</span>}</h3>
      {error && <p className="error small" role="alert" data-testid="csr-choices-error">{error}</p>}
      {said && <p className="ok small" data-testid="csr-choices-said">{said}</p>}
      {pool?.grants?.length > 0 && (
        <p className="dim small">From {pool.grants.map((g) => `${g.offeringName} (${g.choices})`).join(', ')}
          {pool.swapsThisQuarter > 0 && ` · ${pool.swapsThisQuarter} swap${pool.swapsThisQuarter === 1 ? '' : 's'} this quarter`}</p>
      )}
      {(pool?.allocations || []).map((a) => {
        const product = productOf(a);
        return (
          <div className="row" key={a.id} data-testid="csr-choice-row" style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
            <span style={{ flex: 1 }}>{a.offeringName}</span>
            <span className="dim small" data-testid="csr-choice-state">
              {a.state === 'held' ? 'activating — waiting for the partner' : 'active'}{a.heldUntil ? ` · held until ${a.heldUntil}` : ''}
            </span>
            {canAct && a.state === 'committed' && !a.heldUntil && product && (
              <>
                <select aria-label={`Swap ${a.offeringName}`} data-testid="csr-swap-for" disabled={busy} value=""
                  onChange={(e) => {
                    const to = shelf.find((b) => b.id === e.target.value);
                    if (to) run(`Swapping ${a.offeringName} for ${to.name}`, () => bss.swapBenefit({ subscriptionId: product.id, targetOfferingId: to.id }));
                  }}>
                  <option value="">Swap for…</option>
                  {shelf.filter((b) => !picked.has(b.id)).map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
                </select>
                <button className="ghost small" data-testid="csr-release" disabled={busy}
                  onClick={() => run(`${a.offeringName} released — the Choice is free again`, () => authFetch(ORDERS, {
                    method: 'POST', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ relatedParty: [{ id, role: 'customer' }],
                      productOrderItem: [{ id: '1', action: 'delete', product: { id: product.id } }] }),
                  }).then(json))}>
                  Release
                </button>
              </>
            )}
          </div>
        );
      })}
      {canAct && pool && pool.left > 0 && (
        <div className="row" style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 6 }}>
          {shelf.filter((b) => !picked.has(b.id) && b.choiceCost <= pool.left).map((b) => (
            <button key={b.id} className="ghost small" data-testid="csr-pick" disabled={busy}
              title={b.description || ''}
              onClick={() => run(`Picking ${b.name}`, () => bss.pickBenefit({ customerId: id, benefitOfferingId: b.id }))}>
              Pick {b.name}
            </button>
          ))}
        </div>
      )}
      {pool && (
        <details style={{ marginTop: 6 }}>
          <summary className="small" data-testid="csr-goodwill">Goodwill {label === 'Choices' ? 'Choice' : label}…</summary>
          <p className="dim small">One more for this household, for a reason — someone holding the goodwill approver role decides it.</p>
          <input data-testid="csr-goodwill-reason" placeholder="Why — e.g. the outage on 26 October" value={reason}
            onChange={(e) => setReason(e.target.value)} style={{ width: '60%' }} />
          <button className="ghost small" data-testid="csr-goodwill-ask" disabled={busy || !reason.trim()}
            onClick={() => run('Goodwill asked for', () => bss.grantGoodwillChoice({ customerId: id, reason: reason.trim() })).then(() => setReason(''))}>
            Ask
          </button>
        </details>
      )}
    </section>
  );
}
