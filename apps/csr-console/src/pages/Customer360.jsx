import { useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { aiCustomerSummary, appointmentsOf, billsOf, cartsOf, getCustomer,
  interactionsPage, logInteraction, ordersOf, patchOrder, productsOf, ticketsOf,
  activeServicesOf, agreementsOf, completeCutover, paymentMethodsOf,
  portingOrdersOf, recommendationsOf, redemptionsOf, usageOf, aiNextBestOffer, orderForCustomer, sendOffer,
  verifyPartyAddress, autoTopupOf, creditDecisionsOf, directorySettingsOf, linkRegistryPerson,
  poolsOf, runRegistrySync, saveDirectorySetting, spendPoliciesOf } from '../api.js';
import TicketCard from './TicketCard.jsx';
import Assist from './Assist.jsx';
import { rememberRecent } from './Customers.jsx';
import NewMenu from './customer/NewMenu.jsx';
import Activity, { timelineOf, dt, chan } from './customer/Activity.jsx';
import { ServiceFacts, ServiceActions, DangerZone, Diagnosis, numberOf, serviceKind } from './customer/ServiceRows.jsx';
import { Bills, Usage, Agreements, PromoAndPayment, Policies, Pool, AutoTopup, CreditDecisions, accountState, OPEN_BILL_STATES, due } from './customer/Money.jsx';
import { GenAlpha } from '../sdk/genalpha-sdk.js';
import { hasRole, currentTokenValue, authFetch } from '../auth.js';

/* The customer workspace. Five stable areas, one customer context:
 *   Overview   — who is this, what do they have, is anything wrong, what is in
 *                progress, what happened, what next. Exception-first: normal is
 *                quiet, a problem is loud and carries its next action.
 *   Services   — every product and the service under it, with the actions that
 *                service can take; the danger zone at the end.
 *   Activity   — one chronological story across interactions, orders, tickets, ports.
 *   Billing & account — bills, usage, agreements, cards, caps, pool, credit.
 *   More       — registry, directory listing, appointments, identifiers.
 * GenAlpha Assist stays beside the customer in every area. Errors land next to
 * the block that raised them and in a toast the eye cannot miss. */

// the desk programs against business actions, not endpoints: the generated ontology SDK, the agent's own token
const bss = new GenAlpha({ baseUrl: '', token: () => currentTokenValue(), channel: 'care' });

const None = () => <span className="secnone"> — none</span>;
const party = (id) => [{ id, role: 'customer', '@referredType': 'Individual' }];
const AREAS = [['overview', 'Overview'], ['services', 'Services'], ['activity', 'Activity'], ['billing', 'Billing & account'], ['more', 'More']];
const LIVE_ORDER = (o) => !['completed', 'cancelled', 'rejected', 'failed', 'partial'].includes(o.state);
const CANCELLABLE = (o) => ['acknowledged', 'pending', 'held', 'inProgress', 'assessingCancellation'].includes(o.state);

// A bundle order decomposes into product families — the agent sees the same
// component tree the customer does, so "why is it in progress" answers itself.
const FAMILY_ICON = { internet: '🌐', tv: '📺', mobile: '📱', device: '📦', other: '•' };
function orderLeaves(items, into = []) {
  for (const it of items || []) {
    const kids = it.productOrderItem || [];
    if (kids.length) orderLeaves(kids, into);
    else if (it.productOffering) into.push(it);
  }
  return into;
}
function familyOf(item) {
  if (item.componentType) return item.componentType;
  const n = (item.productOffering?.name || '').toLowerCase();
  if (/fiber|fibre|broadband|internet|dsl/.test(n)) return 'internet';
  if (/\btv\b|stream|sports|entertain|kids tv/.test(n)) return 'tv';
  if (/mobile|sim|5g|data|gb\b/.test(n)) return 'mobile';
  if (/iphone|galaxy|pixel|phone|handset|watch/.test(n)) return 'device';
  return 'other';
}
const leafGlyph = (st) => st === 'completed' ? '✓' : st === 'cancelled' ? '✗' : '⏳';

/** Products are what the customer bought; services are what runs. One row per product, its service beneath it, orphans after. */
function mergeProductsAndServices(products, services) {
  const free = [...services];
  const rows = [];
  const seen = new Map(); // name -> row, so twenty identical top-ups read as one row ×20
  for (const p of products) {
    const i = free.findIndex((sv) => (sv.name || '').toLowerCase() === (p.name || '').toLowerCase());
    const sv = i >= 0 ? free.splice(i, 1)[0] : null;
    const key = p.name + '|' + (sv ? numberOf(sv) || sv.id : '') + '|' + p.status;
    if (!sv && seen.has(key)) { seen.get(key).count++; continue; }
    const row = { product: p, service: sv, count: 1 };
    rows.push(row);
    if (!sv) seen.set(key, row);
  }
  for (const sv of free) rows.push({ product: null, service: sv, count: 1 });
  return rows;
}

export default function Customer360() {
  const { id } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const area = AREAS.some(([k]) => k === location.hash.slice(1)) ? location.hash.slice(1) : 'overview';
  const go = (a) => navigate(`/customer/${id}${a === 'overview' ? '' : '#' + a}`);

  const [customer, setCustomer] = useState(null);
  const [regCheck, setRegCheck] = useState(null);
  const [orders, setOrders] = useState([]);
  const [products, setProducts] = useState([]);
  const [bills, setBills] = useState([]);
  const [appointments, setAppointments] = useState([]);
  const [tickets, setTickets] = useState([]);
  const [carts, setCarts] = useState([]);
  const [interactions, setInteractions] = useState([]);
  const [interactionsTotal, setInteractionsTotal] = useState(0);
  const [diagnosis, setDiagnosis] = useState(null);
  const [upgrade, setUpgrade] = useState(null); // {productId, name, options, verdicts, said} — the ontology's answer
  const [usage, setUsage] = useState([]);
  const [agreements, setAgreements] = useState([]);
  const [activeServices, setActiveServices] = useState([]);
  const [portingOrders, setPortingOrders] = useState([]);
  const [redemptions, setRedemptions] = useState([]);
  const [methods, setMethods] = useState([]);
  const [suggestions, setSuggestions] = useState([]);
  const [note, setNote] = useState('');
  const [errors, setErrors] = useState({}); // scope -> message: the error lands next to the block that raised it
  const [toast, setToast] = useState(null);
  const [copilot, setCopilot] = useState(null);
  const [spendPolicies, setSpendPolicies] = useState([]);
  const [pools, setPools] = useState([]);
  const [autoTopup, setAutoTopup] = useState(null);
  const [creditDecisions, setCreditDecisions] = useState([]);
  const [dirSettings, setDirSettings] = useState([]);
  const [dirSaved, setDirSaved] = useState(null);
  const [personRef, setPersonRef] = useState('');
  const [syncNote, setSyncNote] = useState(null);
  const [dirDraft, setDirDraft] = useState({ serviceRef: '', exposure: 'partial', secretNumber: false });
  const [nbo, setNbo] = useState(null);
  const [puks, setPuks] = useState({});
  const [allOrders, setAllOrders] = useState(false);
  const [version, setVersion] = useState(0);
  const [openedAt] = useState(() => new Date().toISOString());

  async function summarize() {
    setCopilot('loading');
    try {
      setCopilot(await aiCustomerSummary({
        customerName: `${customer.givenName} ${customer.familyName}`,
        context: {
          orders: orders.map((o) => ({ state: o.state, at: o.orderDate })),
          activeServices: activeServices.length,
          bills: bills.map((b) => ({ state: b.state, amountDue: b.amountDue })),
          openTickets: tickets.filter((t) => t.status !== 'closed').map((t) => ({ name: t.name, status: t.status, severity: t.severity })),
          usage: usage.map((u) => ({ meter: u.name, used: u.used, included: u.included })),
          agreements: agreements.map((a) => ({ name: a.name, endsAt: a.completionDate })),
          openCart: carts.some((c) => c.status === 'active'),
          lastInteractions: interactions.slice(0, 5).map((i) => i.description),
        },
      }));
    } catch (e) {
      setCopilot(null);
      fail('page', 'Copilot: ' + e.message);
    }
  }

  // the live part of the record: what changes while the customer is on the line
  const reloadLive = () => {
    ordersOf(id).then(setOrders).catch(() => {});
    ticketsOf(id).then(setTickets).catch(() => {});
    cartsOf(id).then(setCarts).catch(() => {});
    activeServicesOf(id).then(setActiveServices);
    portingOrdersOf(id).then(setPortingOrders);
    appointmentsOf(id).then(setAppointments).catch(() => {});
  };
  const reload = () => {
    getCustomer(id).then((c) => { setCustomer(c); rememberRecent(c); }).catch((e) => fail('page', e.message));
    reloadLive();
    productsOf(id).then(setProducts).catch(() => {});
    billsOf(id).then(setBills).catch(() => {});
    interactionsPage(id, 0).then(({ items, total }) => { setInteractions(items); setInteractionsTotal(total); }).catch(() => {});
    usageOf(id).then(setUsage);
    agreementsOf(id).then(setAgreements);
    redemptionsOf(id).then(setRedemptions);
    paymentMethodsOf(id).then(setMethods);
    recommendationsOf(id).then(setSuggestions);
    spendPoliciesOf(id).then(setSpendPolicies);
    poolsOf(id).then(setPools);
    autoTopupOf(id).then(setAutoTopup);
    creditDecisionsOf(id).then(setCreditDecisions);
    directorySettingsOf(id).then(setDirSettings);
  };
  useEffect(reload, [id]);
  // orders complete, tickets move and lines change while the page is open — the desk never shows a stale state for long
  useEffect(() => {
    const t = setInterval(() => { if (document.visibilityState === 'visible') reloadLive(); }, 20000);
    return () => clearInterval(t);
  }, [id]);
  useEffect(() => { if (!toast) return undefined; const t = setTimeout(() => setToast(null), 9000); return () => clearTimeout(t); }, [toast]);

  const fail = (scope, message) => { setErrors((e) => ({ ...e, [scope]: message })); setToast({ scope, message }); };
  async function act(fn, scope = 'page') {
    try {
      setErrors((e) => { const n = { ...e }; delete n[scope]; return n; });
      await fn();
      reload();
      setVersion((v) => v + 1);
    } catch (e) {
      fail(scope, e.message);
    }
  }
  const Err = ({ scope }) => errors[scope] ? (
    <p className="error inline" data-testid={`error-${scope}`} role="alert">{errors[scope]}
      <button className="linkish small" onClick={() => setErrors((e) => { const n = { ...e }; delete n[scope]; return n; })} aria-label="Dismiss">×</button></p>
  ) : null;

  const checkCancel = async (orderId) => {
    if (bss.checkCancelOrder) return bss.checkCancelOrder({ orderId });
    const res = await authFetch('/ontology/v1/actions/cancelOrder/check', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Channel': 'care' }, body: JSON.stringify({ orderId }) });
    return res.json();
  };
  const cancelOrder = (o) => act(async () => {
    const check = await checkCancel(o.id);
    if (!check.allowed) throw new Error(`Cannot cancel ${o.description || o.id.slice(0, 8)}: ${check.refusal || 'the order is past the point of cancelling'}. The list has been refreshed.`);
    if (!window.confirm(`Cancel order ${o.description || o.id.slice(0, 8)}?`)) return;
    await bss.cancelOrder({ orderId: o.id });
    await logInteraction({ description: `Order ${o.description || o.id.slice(0, 8)} cancelled on request`, channel: 'phone', direction: 'inbound', sourceSystem: 'csr-console', relatedParty: party(id) });
  }, 'orders');

  const derived = useMemo(() => {
    // a resolved ticket is still open work until the customer closes it — it stays in front of the agent
    const openTickets = tickets.filter((t) => t.status !== 'closed');
    const liveOrders = orders.filter(LIVE_ORDER);
    const livePorts = portingOrders.filter((po) => !['completed', 'cancelled', 'rejected', 'failed'].includes(po.status));
    const upcoming = appointments.filter((ap) => !['completed', 'cancelled'].includes(ap.status));
    const cartLines = carts.filter((c) => c.status === 'active').flatMap((c) => (c.cartItem || []).map((line) => ({ cart: c, line })));
    const openBills = bills.filter((b) => OPEN_BILL_STATES.includes(b.state) && due(b) > 0);
    const rows = mergeProductsAndServices(products, activeServices);
    return { openTickets, liveOrders, livePorts, upcoming, cartLines, openBills, rows };
  }, [tickets, orders, portingOrders, appointments, carts, bills, products, activeServices]);
  const { openTickets, liveOrders, livePorts, upcoming, cartLines, openBills, rows } = derived;
  const openWork = openTickets.length + liveOrders.length + livePorts.length + upcoming.length + (cartLines.length ? 1 : 0);
  const account = useMemo(() => accountState({ bills, spendPolicies, usage, creditDecisions, methods }), [bills, spendPolicies, usage, creditDecisions, methods]);
  const accountWarns = account.filter((l) => l.level === 'warn').length;

  if (!customer) return errors.page ? <p className="error">{errors.page}</p> : <p className="dim">Loading…</p>;

  const address = (customer.contactMedium || []).find((m) => m.mediumType === 'postalAddress' && m.characteristic?.source !== 'folkeregisteret')?.characteristic;
  const registered = (customer.contactMedium || []).find((m) => m.mediumType === 'postalAddress' && m.characteristic?.source === 'folkeregisteret')?.characteristic;
  const email = (customer.contactMedium || []).find((m) => m.mediumType === 'email')?.characteristic?.emailAddress;
  const numbers = [...new Set(activeServices.flatMap((sv) => sv.supportingResource || []).map((r) => r.value).filter(Boolean))];
  const addressProtected = customer.addressProtected === true;
  const showableAddress = !addressProtected && address && address.street1 ? address : null;
  const paused = activeServices.filter((s) => s.state === 'suspended');
  const overAllowance = usage.filter((b) => b.allowedValue != null && Number(b.usedValue) > Number(b.allowedValue));
  const focusNote = () => { const el = document.querySelector('input[name="newInteraction"]'); if (el) { el.focus(); el.scrollIntoView({ block: 'center' }); } };

  const noteForm = (
    <form className="stack" onSubmit={(e) => {
      e.preventDefault();
      if (!note.trim()) return;
      act(() => logInteraction({ description: note.trim(), channel: 'phone', direction: 'inbound', relatedParty: party(id) }), 'activity');
      setNote('');
    }}>
      <input name="newInteraction" placeholder="Log a contact (call, chat, visit)…" value={note} onChange={(e) => setNote(e.target.value)} aria-label="Log a contact" />
      <button className="ghost" type="submit">Log interaction</button>
    </form>
  );

  const upgradeCard = upgrade && (
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

  const upgradeButton = (p) => p && p.status === 'active' && hasRole('ordering:write') && (
    <button className="ghost" data-testid={`csr-upgrade-options-${p.id}`}
        title="What this could become — from the operational ontology, with every condition checked before anything changes"
        onClick={() => act(async () => {
          const options = await bss.availableUpgrades(p.id);
          setUpgrade({ productId: p.id, name: p.name, options, verdicts: null, said: null });
        }, 'services')}>
      Upgrade options
    </button>
  );

  const serviceRows = (full) => (
    <div className="rows services" data-testid="services-list">
      {!rows.length && <p className="dim small">No products or services on this customer.</p>}
      {(full ? rows : rows.slice(0, 6)).map(({ product: p, service: sv, count }) => (
        <div className={`row svc ${sv ? serviceKind(sv) : 'product'}`} key={(p && p.id) || sv.id} data-testid={sv && numberOf(sv) ? 'service-number' : undefined}>
          <div>
            <strong>{(p || sv).name}</strong>{count > 1 && <span className="dim small"> ×{count}</span>}
            {sv && <div><ServiceFacts sv={sv} usage={usage} /></div>}
            {!sv && p && <div className="dim small">product · no running service under it</div>}
          </div>
          {sv ? (
            <ServiceActions sv={sv} id={id} act={act} puks={puks} setPuks={setPuks} onDiagnosis={setDiagnosis} compact={!full}
              extra={upgradeButton(p)} />
          ) : (
            <div className="rowend"><span className={`state ${p.status}`}>{p.status}</span>{upgradeButton(p)}</div>
          )}
        </div>
      ))}
      {!full && rows.length > 6 && <button className="linkish" data-testid="services-all" onClick={() => go('services')}>All {rows.length} products and services →</button>}
      {upgradeCard}
      <Err scope="services" />
      <Diagnosis diagnosis={diagnosis} />
    </div>
  );

  const ordersBlock = (list) => (
    <div className="rows" data-testid="orders-card">
      <Err scope="orders" />
      {list.map((o) => {
        const leaves = orderLeaves(o.productOrderItem);
        const multi = leaves.length > 1;
        const doneCount = leaves.filter((l) => l.state === 'completed').length;
        return (
          <div className="row" key={o.id}>
            <div>
              <strong>{o.description || o.id}</strong>
              <div className="dim small">{dt(o.orderDate)}{multi && o.state === 'partiallyCompleted' && <> · {doneCount}/{leaves.length} components ready</>}</div>
              {multi && (
                <ul className="ordercomponents" data-testid="order-components">
                  {leaves.map((l, i) => {
                    const rel = (l.orderItemRelationship || []).find((r) => r.relationshipType === 'reliesOn');
                    const base = rel && leaves.find((x) => x.id === rel.id);
                    const waiting = base && base.state !== 'completed' && l.state !== 'completed';
                    return (
                      <li key={l.id || i}>
                        <span className="ocfam">{FAMILY_ICON[familyOf(l)] || '•'}</span>
                        <span className="ocname">{l.productOffering?.name || 'component'}</span>
                        <span className={`ocstate ${l.state}`}>{leafGlyph(l.state)} {l.state}
                          {waiting && <span className="dim"> · waiting on {familyOf(base) === 'internet' ? 'broadband' : (base.productOffering?.name || 'base')}</span>}</span>
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>
            <div className="rowend">
              <span className={`state ${o.state}`}>{o.state}</span>
              {o.state === 'acknowledged' && hasRole('ordering:write') && (
                <button className="ghost" onClick={() => act(() => patchOrder(o.id, { state: 'completed' }), 'orders')}>Complete</button>
              )}
              {CANCELLABLE(o) && hasRole('ordering:write') && (
                <button className="ghost danger" data-testid={`cancel-order-${o.id}`} title="Checked with the ontology first — a completed order is refused in words, here" onClick={() => cancelOrder(o)}>Cancel</button>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );

  const portingBlock = portingOrders.length > 0 && (
    <>
      <h3>Number porting</h3>
      <div className="rows" data-testid="porting-card">
        {portingOrders.map((po) => (
          <div className="row" key={po.id}>
            <div>
              <span className="msisdn">{po.phoneNumber}</span>
              <div className="dim small">{po.direction === 'portOut' ? 'Port-out to' : 'Port-in from'} {po.otherOperator || '—'}{' · '}{po.country}</div>
            </div>
            <div className="rowend">
              <span className={`state ${po.status}`}>{po.status}</span>
              {po.status === 'scheduled' && hasRole('porting:write') && (
                <button className="ghost" data-testid="complete-cutover" onClick={() => act(() => completeCutover(po.id), 'orders')}>Complete cutover</button>
              )}
            </div>
          </div>
        ))}
      </div>
    </>
  );

  const cartBlock = cartLines.length > 0 && (
    <>
      <h3>Cart, not yet ordered</h3>
      <div className="rows" data-testid="cart-card">
        {cartLines.map(({ cart, line }) => (
          <div className="row" key={cart.id + line.key}>
            <div>
              <span>{line.name}</span>
              {(line.selections || []).map((sel) => (
                <div className="dim small" key={sel.offeringId}>{sel.name}{Object.values(sel.characteristics || {}).map((v) => ` · ${v}`).join('')}</div>
              ))}
            </div>
            <span className="dim small">× {line.quantity}</span>
          </div>
        ))}
        <p className="dim small">The customer's cart, live — opened {dt(cartLines[0].cart.creationDate)}. Assisted checkout starts here; nothing is ordered until they, or you with their say-so, place it.</p>
      </div>
    </>
  );

  const recent = timelineOf({ interactions, orders: orders.slice(0, 5), tickets: tickets.slice(0, 5), portingOrders: portingOrders.slice(0, 3) });

  return (
    <>
      {toast && (
        <div className="toast error" role="alert" data-testid="toast">
          <span>{toast.message}</span>
          <button className="linkish small" onClick={() => setToast(null)} aria-label="Dismiss">×</button>
        </div>
      )}
      <Err scope="page" />
      <h1>
        <span className="avatar big">{(customer.givenName?.[0] || '?').toUpperCase()}{(customer.familyName?.[0] || '').toUpperCase()}</span>
        {customer.givenName} {customer.familyName}
      </h1>
      {customer.deceased === true && (
        <div className="notice danger" data-testid="deceased-flag">
          Deceased — the registry reports this customer as deceased. Route the matter to estate handling; do not market, dun, or make outbound contact.
        </div>
      )}
      {addressProtected && (
        <div className="notice protectednote" data-testid="address-protected">
          Address protected — do not request or record it. Deliveries go to a pickup point; the address appears in no console, export or directory.
        </div>
      )}
      <p className="dim small identity-line">
        {email || <span title={customer.id}>{customer.id.slice(0, 8)}…</span>}
        {numbers.length > 0 && <> · <span data-testid="cust-numbers">
          📞 {numbers.slice(0, 4).map((n) => <span key={n} className="msisdn" style={{ marginRight: 6 }}>{n}</span>)}
          {numbers.length > 4 && <span className="dim">+{numbers.length - 4} more under Services</span>}
        </span></>}
        {showableAddress && <> · {showableAddress.street1}, {showableAddress.postCode} {showableAddress.city}</>}
        {!addressProtected && registered && <span className="ok" data-testid="registered-hint"> · ✓ registered address on file</span>}
        {showableAddress && (
          <> · <button className="linkish small" data-testid="reverify-address" disabled={regCheck === 'checking'}
            onClick={async () => {
              setRegCheck('checking');
              try { const m = await verifyPartyAddress(customer, address); setRegCheck(m ? m.outcome : 'unavailable'); } catch { setRegCheck('unavailable'); }
            }}>{regCheck === 'checking' ? 'Checking register…' : 'Re-verify address'}</button>
          {regCheck && regCheck !== 'checking' && (
            <span data-testid="reverify-result" className={regCheck === 'match' ? 'ok' : 'dim'}>
              {' '}{regCheck === 'match' ? '✓ matches the national register' : regCheck === 'unavailable' ? 'no registry for this market' : 'could not be verified against the register'}
            </span>
          )}</>
        )}
      </p>
      <NewMenu id={id} customer={customer} suggestions={suggestions} act={act} onNote={() => { go('overview'); setTimeout(focusNote, 80); }} />
      <Err scope="new" />

      <nav className="areas" aria-label="Customer areas" data-testid="areas">
        {AREAS.map(([k, label]) => (
          <button key={k} className={area === k ? 'area on' : 'area'} data-testid={`area-${k}`} onClick={() => go(k)} aria-current={area === k ? 'page' : undefined}>
            {label}
            {k === 'overview' && openWork > 0 && <span className="badge warn">{openWork}</span>}
            {k === 'services' && rows.length > 0 && <span className="badge">{rows.length}</span>}
            {k === 'billing' && accountWarns > 0 && <span className="badge warn">{accountWarns}</span>}
          </button>
        ))}
      </nav>

      <div className="cockpit" data-testid="cockpit">
        <div className="cockpit-main" data-testid={`area-view-${area}`}>
          {area === 'overview' && (
            <>
              {/* summary: normal is quiet, exceptions say what to do */}
              <div className="now" data-testid="zone-now">
                <button className="now-card clickable" data-testid="now-work" onClick={() => document.getElementById('open-work')?.scrollIntoView({ block: 'start', behavior: 'smooth' })}>
                  <div className="assist-label">Work</div>
                  {openWork === 0 ? <p className="ok small">No open work</p> : (
                    <>
                      {openTickets.slice(0, 2).map((t) => <p key={t.id} className="small"><strong>{t.name}</strong> <span className={`state ${t.status}`}>{t.status}</span></p>)}
                      {liveOrders.slice(0, 2).map((o) => <p key={o.id} className="small">Order in progress: <strong>{o.description || o.id.slice(0, 8)}</strong> <span className={`state ${o.state}`}>{o.state}</span></p>)}
                      {livePorts.slice(0, 1).map((po) => <p key={po.id} className="small">Port {po.status}: <span className="msisdn">{po.phoneNumber}</span></p>)}
                      {upcoming.slice(0, 1).map((ap) => <p key={ap.id} className="small">Visit {dt(ap.validFor?.startDateTime)} · {ap.status}</p>)}
                      {cartLines.length > 0 && <p className="small">Cart open: {cartLines.length} item{cartLines.length === 1 ? '' : 's'}, not ordered</p>}
                    </>
                  )}
                </button>
                <button className="now-card clickable" data-testid="now-account" onClick={() => go('billing')}>
                  <div className="assist-label">Account</div>
                  {account.filter((l) => l.level === 'warn').slice(0, 3).map((l, i) => <p key={i} className="error small">{l.text}</p>)}
                  {!accountWarns && <p className="ok small">Account OK</p>}
                  <p className="dim small">
                    <span data-testid="usage-card">{usage.length ? `${usage.length} meter${usage.length === 1 ? '' : 's'}${overAllowance.length ? '' : ' within allowance'}` : 'no usage this month'}</span>
                    {' · '}<span data-testid="agreements-card">{agreements.length ? `${agreements.length} agreement${agreements.length === 1 ? '' : 's'}` : 'no agreements'}</span>
                    {' · '}<span data-testid="promo-vault-card">{methods.length || redemptions.length ? `${methods.length} card${methods.length === 1 ? '' : 's'}, ${redemptions.length} promo${redemptions.length === 1 ? '' : 's'}` : 'no cards or promos'}</span>
                  </p>
                </button>
                <button className="now-card clickable" data-testid="now-health" onClick={() => go('services')}>
                  <div className="assist-label">Service health</div>
                  {!activeServices.length && <p className="dim small">No running services</p>}
                  {activeServices.length > 0 && !paused.length && !overAllowance.length && <p className="ok small">All {activeServices.length} service{activeServices.length === 1 ? '' : 's'} healthy</p>}
                  {paused.map((s) => <p key={s.id} className="error small">Paused: {s.name} {numberOf(s) && <span className="msisdn">{numberOf(s)}</span>} — resume under Services</p>)}
                  {overAllowance.map((u, i) => <p key={i} className="error small">Over allowance: {u.name} ({u.usedValue} of {u.allowedValue} {u.units})</p>)}
                </button>
                <div className="now-card" data-testid="now-contact">
                  <div className="assist-label">Last contact</div>
                  {interactions[0] ? (
                    <p className="small">{interactions[0].description || interactions[0].reason}<br /><span className="dim">{chan(interactions[0].channel)} · {dt(interactions[0].interactionDate)}</span></p>
                  ) : <p className="dim small">No contact logged</p>}
                </div>
              </div>

              <div className="zone" data-testid="zone-lines">
                <h2>Services <span className="dim small">{rows.length ? `${rows.length} · ${activeServices.filter((s) => s.state === 'active').length} active` : ''}</span>
                  <button className="linkish small" onClick={() => go('services')}>All actions →</button></h2>
                {serviceRows(false)}
              </div>

              <div className="zone" id="open-work" data-testid="zone-orders">
                <h2>Open work {openWork === 0 && <span className="dim small">— none</span>}</h2>
                {openTickets.length > 0 && <>
                  <h3>Open tickets</h3>
                  {openTickets.map((t) => <TicketCard key={t.id} ticket={t} onChanged={reload} />)}
                </>}
                {liveOrders.length > 0 && <><h3>Orders in progress</h3>{ordersBlock(liveOrders)}</>}
                {!liveOrders.length && errors.orders && <Err scope="orders" />}
                {livePorts.length > 0 && portingBlock}
                {upcoming.length > 0 && (
                  <>
                    <h3>Visits</h3>
                    <div className="rows">
                      {upcoming.map((ap) => (
                        <div className="row" key={ap.id}><span>{ap.description || 'Visit'}</span><span className="rowend"><span className="dim small">{dt(ap.validFor?.startDateTime)}</span><span className={`state ${ap.status}`}>{ap.status}</span></span></div>
                      ))}
                    </div>
                  </>
                )}
                {cartBlock}
                {!livePorts.length && portingBlock}
              </div>

              <div className="zone" data-testid="zone-timeline">
                <h2>Recent activity <button className="linkish small" onClick={() => go('activity')}>View all activity →</button></h2>
                <div className="rows">
                  {!recent.length && <p className="dim small">Nothing on record yet.</p>}
                  {recent.map((e, i) => (
                    <div className="row" key={e.kind + (e.ref.id || i)}>
                      <div>
                        <span className={`chip kind ${e.kind}`}>{e.kind}</span> <span>{e.title}</span>
                        <div className="dim small">{dt(e.at)}{e.meta ? ` · ${e.meta}` : ''}</div>
                      </div>
                      {e.state && <span className={`state ${e.state}`}>{e.state}</span>}
                    </div>
                  ))}
                  {interactions.length < interactionsTotal && (
                    <button className="ghost" data-testid="more-interactions"
                      onClick={() => interactionsPage(id, interactions.length).then(({ items, total }) => { setInteractions((prev) => [...prev, ...items]); setInteractionsTotal(total); }).catch(() => {})}>
                      Show more ({interactionsTotal - interactions.length} older) ↓
                    </button>
                  )}
                  {interactions.length > 5 && <button className="ghost" data-testid="fewer-interactions" onClick={() => setInteractions((prev) => prev.slice(0, 5))}>Show less ↑</button>}
                </div>
                <Err scope="activity" />
                {noteForm}
              </div>
            </>
          )}

          {area === 'services' && (
            <>
              <h2>Services <span className="dim small">{rows.length ? `${rows.length} · ${activeServices.filter((s) => s.state === 'active').length} active${paused.length ? ` · ${paused.length} paused` : ''}` : ''}</span></h2>
              <p className="zone-note">What each service is decides what it can do here: a PUK belongs to a SIM, a line check to a line. Two direct actions, the rest under More…, ceasing in the danger zone.</p>
              {serviceRows(true)}
              <DangerZone services={activeServices} agreements={agreements} id={id} act={act} />
            </>
          )}

          {area === 'activity' && (
            <>
              <h2>Activity</h2>
              <Activity interactions={interactions} interactionsTotal={interactionsTotal} orders={orders} tickets={tickets} portingOrders={portingOrders} reload={reload}
                onMoreInteractions={() => interactionsPage(id, interactions.length).then(({ items, total }) => { setInteractions((prev) => [...prev, ...items]); setInteractionsTotal(total); }).catch(() => {})}
                onFewerInteractions={() => setInteractions((prev) => prev.slice(0, 5))} />
              {orders.length > 6 && (
                <details className="more-orders">
                  <summary className="dim small">All {orders.length} orders as a list</summary>
                  <button className="ghost small" data-testid="orders-all" onClick={() => setAllOrders((x) => !x)}>{allOrders ? 'Newest only' : `Show all ${orders.length}`}</button>
                  {ordersBlock(allOrders ? orders : orders.slice(0, 6))}
                </details>
              )}
              <Err scope="activity" />
              {noteForm}
            </>
          )}

          {area === 'billing' && (
            <>
              <h2>Billing &amp; account</h2>
              <p className="zone-note">State first: {account.map((l) => l.text).join(' · ') || 'nothing on file'}.</p>
              <Err scope="billing" />
              <Bills bills={bills} id={id} act={act} />
              <Usage usage={usage} />
              <PromoAndPayment redemptions={redemptions} methods={methods} act={act} />
              <Policies spendPolicies={spendPolicies} id={id} act={act} />
              <Agreements agreements={agreements} />
              <Pool pools={pools} id={id} />
              <AutoTopup autoTopup={autoTopup} />
              <CreditDecisions creditDecisions={creditDecisions} />
            </>
          )}

          {area === 'more' && (
            <>
              <h2>More</h2>
              <Err scope="more" />
              {hasRole('party:write') ? (
                <div data-testid="registry-tools">
                  <h3>Directory listing{!dirSettings.length && <None />}</h3>
                  <div className="rows" data-testid="directory-card">
                    {dirSettings.map((s) => (
                      <div className="row" key={s.id} data-testid="directory-setting-row">
                        <span>{s.serviceRef || 'all services'}</span>
                        <div className="rowend">
                          {s.secretNumber === true && <span className="state cancelled">secret number</span>}
                          <span className={`state ${s.exposure === 'reserved' ? 'cancelled' : s.exposure === 'full' ? 'active' : ''}`}>{s.exposure}</span>
                        </div>
                      </div>
                    ))}
                    {!dirSettings.length && <p className="dim small">No directory choices recorded — the export applies the defaults.</p>}
                  </div>
                  <form className="stack" data-testid="directory-form" onSubmit={(e) => {
                    e.preventDefault();
                    act(async () => {
                      await saveDirectorySetting(id, {
                        ...(dirDraft.serviceRef.trim() ? { serviceRef: dirDraft.serviceRef.trim() } : {}),
                        exposure: dirDraft.exposure, secretNumber: dirDraft.secretNumber,
                      });
                      setDirSaved(`Listing saved: ${dirDraft.exposure}${dirDraft.secretNumber ? ', secret number' : ''}${dirDraft.serviceRef.trim() ? ` for ${dirDraft.serviceRef.trim()}` : ' for all services'}.`);
                      await logInteraction({
                        description: `Directory listing set to ${dirDraft.exposure}` + (dirDraft.secretNumber ? ' with secret number' : '') + (dirDraft.serviceRef.trim() ? ` for ${dirDraft.serviceRef.trim()}` : '') + ' on request',
                        channel: 'phone', direction: 'inbound', sourceSystem: 'csr-console', relatedParty: party(id),
                      });
                    }, 'more');
                  }}>
                    <label className="field"><span className="dim small">Service (blank = all)</span>
                      <input placeholder="service ref" data-testid="directory-service" value={dirDraft.serviceRef} onChange={(e) => setDirDraft((s) => ({ ...s, serviceRef: e.target.value }))} /></label>
                    <label className="field"><span className="dim small">Listing</span>
                      <select data-testid="directory-exposure" value={dirDraft.exposure} onChange={(e) => setDirDraft((s) => ({ ...s, exposure: e.target.value }))}>
                        <option value="full">full listing</option>
                        <option value="partial">partial (no address)</option>
                        <option value="reserved">reserved (unlisted)</option>
                      </select></label>
                    <label className="dim small" style={{ alignSelf: 'center', display: 'flex', alignItems: 'center', gap: 6 }}>
                      <input type="checkbox" data-testid="directory-secret" checked={dirDraft.secretNumber} onChange={(e) => setDirDraft((s) => ({ ...s, secretNumber: e.target.checked }))} />
                      secret number (free; suppresses everything)
                    </label>
                    <button className="primary" type="submit" data-testid="directory-save">Save listing</button>
                  </form>
                  {dirSaved && <p className="ok small" data-testid="directory-saved">{dirSaved}</p>}

                  <h3>Registry link</h3>
                  <form className="stack" onSubmit={(e) => {
                    e.preventDefault();
                    if (!personRef.trim()) return;
                    act(async () => {
                      await linkRegistryPerson(id, personRef.trim());
                      await logInteraction({ description: 'Party linked to national-registry person for ongoing sync', channel: 'phone', direction: 'outbound', sourceSystem: 'csr-console', relatedParty: party(id) });
                    }, 'more');
                    setPersonRef('');
                  }}>
                    <input placeholder="registry person id (personRef)" data-testid="registry-link-input" value={personRef} onChange={(e) => setPersonRef(e.target.value)} aria-label="Registry person id" />
                    <button className="ghost" type="submit" data-testid="registry-link-submit" disabled={!personRef.trim()}>Link registry person</button>
                    <button className="ghost" type="button" data-testid="registry-sync-now" title="Poll the registry event feed now — address changes, protections, deaths"
                      onClick={() => act(async () => {
                        const r = await runRegistrySync();
                        setSyncNote(`Sync ran — processed ${r.processed ?? 0} event${(r.processed ?? 0) === 1 ? '' : 's'}.`);
                      }, 'more')}>Run sync</button>
                  </form>
                  {syncNote && <p className="dim small" data-testid="registry-sync-note">{syncNote}</p>}
                </div>
              ) : <p className="dim small">Registry and directory tools need the party:write right.</p>}

              <h3>Appointments{!appointments.length && <None />}</h3>
              <div className="rows">
                {appointments.map((ap) => (
                  <div className="row" key={ap.id}><span>{ap.description || 'Visit'}</span><span className="rowend"><span className="dim small">{dt(ap.validFor?.startDateTime)}</span><span className={`state ${ap.status}`}>{ap.status}</span></span></div>
                ))}
              </div>

              <h3>Identifiers</h3>
              <p className="dim small mono">customer {customer.id}</p>
            </>
          )}
        </div>

        <div className="cockpit-side">
          <Assist id={id} customer={customer} bss={bss} version={version} act={act}
            nbo={nbo} setNbo={setNbo} aiNextBestOffer={aiNextBestOffer} sendOffer={sendOffer} orderForCustomer={orderForCustomer}
            copilot={copilot} summarize={summarize}
            interactions={interactions} openTickets={openTickets} openedAt={openedAt} />

          <h2>Suggest next</h2>
          <div className="rows" data-testid="suggest-card">
            {suggestions.slice(0, 3).map((it) => (
              <div className="row" key={it.offering.id}>
                <span>{it.offering.name} <span className="dim small">#{it.priority}</span></span>
                <div className="rowend">
                  <button className="ghost" data-testid={`send-offer-${it.offering.id}`} title="A personal message to their inbox (and email, if the tenant sends email)"
                          onClick={() => act(() => sendOffer(id, it.offering, 'Your agent'), 'page')}>Send offer</button>
                  {hasRole('ordering:write') && (
                    <button className="ghost" data-testid={`order-now-${it.offering.id}`} title="Order on the customer's behalf — with their say-so on the line"
                            onClick={() => act(() => orderForCustomer(id, it.offering), 'page')}>Order now</button>
                  )}
                </div>
              </div>
            ))}
            {!suggestions.length && <p className="dim small">Nothing to suggest.</p>}
          </div>
        </div>
      </div>
    </>
  );
}
