import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { aiCustomerSummary, appointmentsOf, billsOf, cartsOf, createTicket, getCustomer,
  interactionsOf, logInteraction, ordersOf, patchOrder, productsOf, ticketsOf, workTicket,
  activeServicesOf, agreementsOf, ceaseService, completeCutover, paymentMethodsOf,
  portingOrdersOf, recommendationsOf, redemptionsOf,
  revokePaymentMethod, usageOf } from '../api.js';
import TicketCard from './TicketCard.jsx';

const dt = (v) => v ? new Date(v).toLocaleString(undefined,
  { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—';

// TMF683 channel is an array of channel references; render it safely whether
// the backend returns the TMF array shape or a plain string.
const chan = (c) => Array.isArray(c)
  ? c.map((x) => (x && typeof x === 'object' ? (x.name || x.id || '') : x)).filter(Boolean).join(', ')
  : (c && typeof c === 'object' ? (c.name || c.id || '') : (c || ''));

export default function Customer360() {
  const { id } = useParams();
  const [customer, setCustomer] = useState(null);
  const [orders, setOrders] = useState([]);
  const [products, setProducts] = useState([]);
  const [bills, setBills] = useState([]);
  const [appointments, setAppointments] = useState([]);
  const [tickets, setTickets] = useState([]);
  const [carts, setCarts] = useState([]);
  const [interactions, setInteractions] = useState([]);
  const [usage, setUsage] = useState([]);
  const [agreements, setAgreements] = useState([]);
  const [activeServices, setActiveServices] = useState([]);
  const [portingOrders, setPortingOrders] = useState([]);
  const [redemptions, setRedemptions] = useState([]);
  const [methods, setMethods] = useState([]);
  const [suggestions, setSuggestions] = useState([]);
  const [note, setNote] = useState('');
  const [ticketName, setTicketName] = useState('');
  const [error, setError] = useState(null);
  const [copilot, setCopilot] = useState(null); // null | 'loading' | {summary, nextActions}

  async function summarize() {
    setCopilot('loading');
    try {
      // The copilot sees exactly what the agent sees — this page's data, trimmed.
      setCopilot(await aiCustomerSummary({
        customerName: `${customer.givenName} ${customer.familyName}`,
        context: {
          orders: orders.map((o) => ({ state: o.state, at: o.orderDate })),
          activeServices: activeServices.length,
          bills: bills.map((b) => ({ state: b.state, amountDue: b.amountDue })),
          openTickets: tickets.filter((t) => t.status !== 'closed')
            .map((t) => ({ name: t.name, status: t.status, severity: t.severity })),
          usage: usage.map((u) => ({ meter: u.name, used: u.used, included: u.included })),
          agreements: agreements.map((a) => ({ name: a.name, endsAt: a.completionDate })),
          openCart: carts.some((c) => c.status === 'active'),
          lastInteractions: interactions.slice(0, 5).map((i) => i.description),
        },
      }));
    } catch (e) {
      setCopilot(null);
      setError('Copilot: ' + e.message);
    }
  }

  const reload = () => {
    getCustomer(id).then(setCustomer).catch((e) => setError(e.message));
    ordersOf(id).then(setOrders).catch(() => {});
    productsOf(id).then(setProducts).catch(() => {});
    billsOf(id).then(setBills).catch(() => {});
    appointmentsOf(id).then(setAppointments).catch(() => {});
    ticketsOf(id).then(setTickets).catch(() => {});
    cartsOf(id).then(setCarts).catch(() => {});
    interactionsOf(id).then(setInteractions).catch(() => {});
    usageOf(id).then(setUsage);
    agreementsOf(id).then(setAgreements);
    activeServicesOf(id).then(setActiveServices);
    portingOrdersOf(id).then(setPortingOrders);
    redemptionsOf(id).then(setRedemptions);
    paymentMethodsOf(id).then(setMethods);
    recommendationsOf(id).then(setSuggestions);
  };
  useEffect(reload, [id]);

  if (error) return <p className="error">{error}</p>;
  if (!customer) return <p className="dim">Loading…</p>;

  const address = (customer.contactMedium || [])
    .find((m) => m.mediumType === 'postalAddress')?.characteristic;

  async function act(fn) {
    try {
      setError(null);
      await fn();
      reload();
    } catch (e) {
      setError(e.message);
    }
  }

  return (
    <>
      <h1>
        <span className="avatar big">{(customer.givenName?.[0] || '?').toUpperCase()}{(customer.familyName?.[0] || '').toUpperCase()}</span>
        {customer.givenName} {customer.familyName}
      </h1>
      <p className="dim small">{customer.id}
        {address && <> · {address.street1}, {address.postCode} {address.city}</>}</p>
      {error && <p className="error">{error}</p>}

      <section className="copilot" data-testid="copilot-card">
        {!copilot && (
          <button className="ghost" data-testid="copilot-summarize" onClick={summarize}>
            ✨ Summarize this customer
          </button>
        )}
        {copilot === 'loading' && <p className="dim small">Copilot is reading the 360…</p>}
        {copilot && copilot !== 'loading' && (
          <>
            <p data-testid="copilot-summary">{copilot.summary}</p>
            <ul className="small">
              {copilot.nextActions.map((a, i) => <li key={i}>{a}</li>)}
            </ul>
            <p className="dim small">Drafted by {copilot.provider} ({copilot.model}) — verify before acting.</p>
          </>
        )}
      </section>

      <div className="col2">
        <section>
          <h2>Orders</h2>
          <div className="rows">
            {orders.map((o) => (
              <div className="row" key={o.id}>
                <div>
                  <strong>{o.description || o.id}</strong>
                  <div className="dim small">{dt(o.orderDate)}</div>
                </div>
                <div className="rowend">
                  <span className={`state ${o.state}`}>{o.state}</span>
                  {o.state === 'acknowledged' && (
                    <>
                      <button className="ghost" onClick={() => act(() => patchOrder(o.id, { state: 'completed' }))}>
                        Complete
                      </button>
                      <button className="ghost danger" onClick={() => act(() => patchOrder(o.id, { state: 'cancelled' }))}>
                        Cancel
                      </button>
                    </>
                  )}
                </div>
              </div>
            ))}
            {!orders.length && <p className="dim small">No orders.</p>}
          </div>

          <h2>Services</h2>
          <div className="rows">
            {products.map((p) => (
              <div className="row" key={p.id}>
                <span>{p.name}</span>
                <span className={`state ${p.status}`}>{p.status}</span>
              </div>
            ))}
            {activeServices.filter((sv) => (sv.supportingResource || []).length).map((sv) => (
              <div className="row" key={sv.id} data-testid="service-number">
                <span className="dim small">{sv.name}</span>
                <div className="rowend">
                  <span className="msisdn">{sv.supportingResource[0].value}</span>
                  <span className={`state ${sv.state}`}>{sv.state}</span>
                  {sv.state === 'active' && (
                    <button className="ghost danger" data-testid="cease-service"
                            onClick={() => window.confirm(`Cease ${sv.name} and release ${sv.supportingResource[0].value}?`)
                              && act(() => ceaseService(sv.id, 'ceased by agent'))}>
                      Cease
                    </button>
                  )}
                </div>
              </div>
            ))}
            {!products.length && <p className="dim small">Nothing provisioned.</p>}
          </div>

          <h2>Number porting</h2>
          <div className="rows" data-testid="porting-card">
            {portingOrders.map((po) => (
              <div className="row" key={po.id}>
                <div>
                  <span className="msisdn">{po.phoneNumber}</span>
                  <div className="dim small">
                    {po.direction === 'portOut' ? 'Port-out to' : 'Port-in from'} {po.otherOperator || '—'}
                    {' · '}{po.country}
                  </div>
                </div>
                <div className="rowend">
                  <span className={`state ${po.status}`}>{po.status}</span>
                  {po.status === 'scheduled' && (
                    <button className="ghost" data-testid="complete-cutover"
                            onClick={() => act(() => completeCutover(po.id))}>
                      Complete cutover
                    </button>
                  )}
                </div>
              </div>
            ))}
            {!portingOrders.length && <p className="dim small">No porting orders.</p>}
          </div>

          <h2>Usage this month</h2>
          <div className="rows" data-testid="usage-card">
            {usage.map((b, i) => (
              <div className="row" key={i}>
                <span>{b.name}</span>
                <span className={b.allowedValue != null && Number(b.usedValue) > Number(b.allowedValue)
                  ? 'error' : 'dim'}>
                  {b.usedValue}{b.allowedValue != null ? ` / ${b.allowedValue}` : ''} {b.units}
                </span>
              </div>
            ))}
            {!usage.length && <p className="dim small">No usage recorded.</p>}
          </div>

          <h2>Agreements</h2>
          <div className="rows" data-testid="agreements-card">
            {agreements.map((g) => (
              <div className="row" key={g.id}>
                <span>{g.name}
                  {g.agreementPeriod?.endDateTime && (
                    <span className="dim small"> — until {g.agreementPeriod.endDateTime.slice(0, 10)}</span>
                  )}
                </span>
                <span className={`state ${g.status}`}>{g.status}</span>
              </div>
            ))}
            {!agreements.length && <p className="dim small">No agreements.</p>}
          </div>

          <h2>Bills</h2>
          <div className="rows">
            {bills.map((b) => (
              <div className="row" key={b.id}>
                <span>{b.billNo}</span>
                <span className="rowend">
                  <span className="linetotal">{b.amountDue.value.toFixed(2)} {b.amountDue.unit}</span>
                  <span className={`state ${b.state}`}>{b.state}</span>
                </span>
              </div>
            ))}
            {!bills.length && <p className="dim small">No bills.</p>}
          </div>

          <h2>Appointments</h2>
          <div className="rows">
            {appointments.map((ap) => (
              <div className="row" key={ap.id}>
                <span>{ap.description || 'Visit'}</span>
                <span className="rowend">
                  <span className="dim small">{dt(ap.validFor?.startDateTime)}</span>
                  <span className={`state ${ap.status}`}>{ap.status}</span>
                </span>
              </div>
            ))}
            {!appointments.length && <p className="dim small">No appointments.</p>}
          </div>
        </section>

        <section>
          {carts.length > 0 && (
            <>
              <h2>Active cart</h2>
              <div className="rows">
                {carts.flatMap((cart) => (cart.cartItem || []).map((line) => (
                  <div className="row" key={cart.id + line.key}>
                    <div>
                      <span>{line.name}</span>
                      {(line.selections || []).map((sel) => (
                        <div className="dim small" key={sel.offeringId}>
                          {sel.name}
                          {Object.values(sel.characteristics || {}).map((v) => ` · ${v}`).join('')}
                        </div>
                      ))}
                    </div>
                    <span className="dim small">× {line.quantity}</span>
                  </div>
                )))}
              </div>
              <p className="dim small">The customer's cart, live — assisted checkout starts here.</p>
            </>
          )}

          <h2>Promotions &amp; payment</h2>
          <div className="rows" data-testid="promo-vault-card">
            {redemptions.map((r) => (
              <div className="row" key={r.id}>
                <span>Promo <strong>{r.code}</strong> — {r.name}</span>
                <span className="dim">−{r.percentage}%</span>
              </div>
            ))}
            {methods.map((m) => (
              <div className="row" key={m.id}>
                <span>{m.details.brand} •••• {m.details.lastFourDigits}
                  <span className="dim small"> exp {m.details.expiry}</span></span>
                <button className="ghost danger"
                        onClick={() => act(() => revokePaymentMethod(m.id))}>Revoke</button>
              </div>
            ))}
            {!redemptions.length && !methods.length
              && <p className="dim small">No promotions or saved cards.</p>}
          </div>

          <h2>Suggest next</h2>
          <div className="rows" data-testid="suggest-card">
            {suggestions.slice(0, 3).map((it) => (
              <div className="row" key={it.offering.id}>
                <span>{it.offering.name}</span>
                <span className="dim small">#{it.priority}</span>
              </div>
            ))}
            {!suggestions.length && <p className="dim small">Nothing to suggest.</p>}
          </div>

          <h2>Tickets</h2>
          {tickets.map((t) => <TicketCard key={t.id} ticket={t} onChanged={reload} />)}
          {!tickets.length && <p className="dim small">No tickets.</p>}
          <form className="stack" onSubmit={(e) => {
            e.preventDefault();
            if (!ticketName.trim()) return;
            act(() => createTicket({
              name: ticketName.trim(),
              severity: 'minor',
              relatedParty: [{ id, role: 'customer', '@referredType': 'Individual' }],
            }));
            setTicketName('');
          }}>
            <input name="newTicket" placeholder="Raise a ticket for this customer…"
                   value={ticketName} onChange={(e) => setTicketName(e.target.value)} />
            <button className="ghost" type="submit">Raise ticket</button>
          </form>

          <h2>Interactions</h2>
          <div className="rows">
            {interactions.map((ix) => (
              <div className="row" key={ix.id}>
                <div>
                  <span>{ix.description || ix.reason}</span>
                  <div className="dim small">{chan(ix.channel)} · {ix.direction} · {dt(ix.interactionDate)}</div>
                </div>
              </div>
            ))}
            {!interactions.length && <p className="dim small">No interactions logged.</p>}
          </div>
          <form className="stack" onSubmit={(e) => {
            e.preventDefault();
            if (!note.trim()) return;
            act(() => logInteraction({
              description: note.trim(),
              channel: 'phone',
              direction: 'inbound',
              relatedParty: [{ id, role: 'customer', '@referredType': 'Individual' }],
            }));
            setNote('');
          }}>
            <input name="newInteraction" placeholder="Log a contact (call, chat, visit)…"
                   value={note} onChange={(e) => setNote(e.target.value)} />
            <button className="ghost" type="submit">Log interaction</button>
          </form>
        </section>
      </div>
    </>
  );
}
