import { useState } from 'react';
import { hasRole } from '../../auth.js';
import { createTicket, sendMessage, orderForCustomer, logInteraction } from '../../api.js';

/* "+ New" — creating something is an action on the customer, not a permanent
 * form in the record. Ticket, message and order are raised here with the
 * customer already filled in; a note has its own always-visible line under
 * Recent activity because it is the one thing agents do on every call. */

const party = (id) => [{ id, role: 'customer', '@referredType': 'Individual' }];

export default function NewMenu({ id, customer, suggestions = [], act, onNote }) {
  const [what, setWhat] = useState(null); // ticket | message | order
  const [ticketName, setTicketName] = useState('');
  const [severity, setSeverity] = useState('minor');
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [offeringId, setOfferingId] = useState('');
  const [done, setDone] = useState(null);
  const close = () => { setWhat(null); setDone(null); };

  return (
    <div className="newmenu" data-testid="new-menu">
      <div className="newmenu-bar">
        <span className="assist-label" style={{ margin: 0 }}>+ New</span>
        <button className={what === 'ticket' ? 'ghost on' : 'ghost'} data-testid="new-ticket" onClick={() => { setWhat(what === 'ticket' ? null : 'ticket'); setDone(null); }}>Ticket</button>
        <button className={what === 'message' ? 'ghost on' : 'ghost'} data-testid="new-message" onClick={() => { setWhat(what === 'message' ? null : 'message'); setDone(null); }}>Message</button>
        {hasRole('ordering:write') && <button className={what === 'order' ? 'ghost on' : 'ghost'} data-testid="new-order" onClick={() => { setWhat(what === 'order' ? null : 'order'); setDone(null); }}>Order</button>}
        <button className="ghost" data-testid="new-note" onClick={onNote}>Note</button>
        {done && <span className="ok small" data-testid="new-done">{done}</span>}
      </div>
      {what === 'ticket' && (
        <form className="stack newmenu-form" data-testid="new-ticket-form" onSubmit={(e) => {
          e.preventDefault();
          if (!ticketName.trim()) return;
          act(async () => {
            await createTicket({ name: ticketName.trim(), severity, ticketType: 'support', relatedParty: party(id) });
            setDone(`Ticket raised for ${customer.givenName} ${customer.familyName}: ${ticketName.trim()}`);
            setTicketName(''); setWhat(null);
          }, 'new');
        }}>
          <span className="dim small">For {customer.givenName} {customer.familyName}</span>
          <input name="newTicket" placeholder="What is wrong? (e.g. No internet since this morning)" value={ticketName} onChange={(e) => setTicketName(e.target.value)} aria-label="Ticket subject" autoFocus />
          <select value={severity} onChange={(e) => setSeverity(e.target.value)} aria-label="Severity" data-testid="new-ticket-severity">
            <option value="minor">minor</option><option value="major">major</option><option value="critical">critical</option>
          </select>
          <button className="primary" type="submit">Raise ticket</button>
          <button className="ghost" type="button" onClick={close}>Cancel</button>
        </form>
      )}
      {what === 'message' && (
        <form className="stack newmenu-form" data-testid="new-message-form" onSubmit={(e) => {
          e.preventDefault();
          if (!subject.trim() || !body.trim()) return;
          act(async () => {
            await sendMessage(id, subject.trim(), body.trim());
            await logInteraction({ description: `Message sent: ${subject.trim()}`, channel: 'phone', direction: 'outbound', sourceSystem: 'csr-console', relatedParty: party(id) });
            setDone(`Message sent to the customer's inbox: ${subject.trim()}`);
            setSubject(''); setBody(''); setWhat(null);
          }, 'new');
        }}>
          <input placeholder="Subject" value={subject} onChange={(e) => setSubject(e.target.value)} aria-label="Message subject" autoFocus />
          <input placeholder="Message to the customer's inbox (and email where the tenant sends it)" value={body} onChange={(e) => setBody(e.target.value)} aria-label="Message body" style={{ flex: 2 }} />
          <button className="primary" type="submit">Send message</button>
          <button className="ghost" type="button" onClick={close}>Cancel</button>
        </form>
      )}
      {what === 'order' && (
        <form className="stack newmenu-form" data-testid="new-order-form" onSubmit={(e) => {
          e.preventDefault();
          const it = suggestions.find((s) => s.offering.id === offeringId);
          if (!it) return;
          act(async () => {
            await orderForCustomer(id, it.offering);
            setDone(`Ordered on the customer's behalf: ${it.offering.name}`);
            setWhat(null);
          }, 'new');
        }}>
          <span className="dim small">With the customer's say-so on the line</span>
          <select value={offeringId} onChange={(e) => setOfferingId(e.target.value)} aria-label="Offering" data-testid="new-order-offering">
            <option value="">Pick an offering…</option>
            {suggestions.map((s) => <option key={s.offering.id} value={s.offering.id}>{s.offering.name}</option>)}
          </select>
          <button className="primary" type="submit" disabled={!offeringId}>Order now</button>
          <button className="ghost" type="button" onClick={close}>Cancel</button>
        </form>
      )}
    </div>
  );
}
