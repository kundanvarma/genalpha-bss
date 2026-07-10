import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { appointmentsOf, billsOf, createTicket, getCustomer, interactionsOf, logInteraction,
  ordersOf, patchOrder, productsOf, ticketsOf, workTicket } from '../api.js';
import TicketCard from './TicketCard.jsx';

const dt = (v) => v ? new Date(v).toLocaleString(undefined,
  { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—';

export default function Customer360() {
  const { id } = useParams();
  const [customer, setCustomer] = useState(null);
  const [orders, setOrders] = useState([]);
  const [products, setProducts] = useState([]);
  const [bills, setBills] = useState([]);
  const [appointments, setAppointments] = useState([]);
  const [tickets, setTickets] = useState([]);
  const [interactions, setInteractions] = useState([]);
  const [note, setNote] = useState('');
  const [ticketName, setTicketName] = useState('');
  const [error, setError] = useState(null);

  const reload = () => {
    getCustomer(id).then(setCustomer).catch((e) => setError(e.message));
    ordersOf(id).then(setOrders).catch(() => {});
    productsOf(id).then(setProducts).catch(() => {});
    billsOf(id).then(setBills).catch(() => {});
    appointmentsOf(id).then(setAppointments).catch(() => {});
    ticketsOf(id).then(setTickets).catch(() => {});
    interactionsOf(id).then(setInteractions).catch(() => {});
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
      <h1>{customer.givenName} {customer.familyName}</h1>
      <p className="dim small">{customer.id}
        {address && <> · {address.street1}, {address.postCode} {address.city}</>}</p>
      {error && <p className="error">{error}</p>}

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
            {!products.length && <p className="dim small">Nothing provisioned.</p>}
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
                  <span>{ix.description}</span>
                  <div className="dim small">{ix.channel} · {ix.direction} · {dt(ix.interactionDate)}</div>
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
