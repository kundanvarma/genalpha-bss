import { useEffect, useState } from 'react';
import { hasRole } from '../../auth.js';
import { createTicket, sendMessage, orderForCustomer, logInteraction, queryConfiguration, checkConfiguration } from '../../api.js';

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
  // the ONE oracle (TMF760): a configurable offering is configured here exactly as the customer would in the shop
  const [space, setSpace] = useState(null);
  const [picks, setPicks] = useState({});
  const [qty, setQty] = useState(1);
  const [verdict, setVerdict] = useState(null);
  useEffect(() => {
    setSpace(null); setPicks({}); setQty(1); setVerdict(null);
    if (!offeringId) return;
    queryConfiguration(offeringId).then((sp) => {
      if (!sp || (!(sp.configurationCharacteristic || []).length && !sp.fungible)) { setSpace(null); return; }
      setSpace(sp);
      const defaults = {};
      for (const c of sp.configurationCharacteristic || []) { const vals = c.productSpecCharacteristicValue || []; const d = vals.find((v) => v.isDefault) || vals.find((v) => v.value != null && v.isSelectable !== false); if (d && d.value != null) defaults[c.name] = d.value; }
      setPicks(defaults);
    }).catch(() => setSpace(null));
  }, [offeringId]);
  useEffect(() => {
    if (!space || !offeringId) return;
    let live = true;
    checkConfiguration(offeringId, picks, qty).then((v) => { if (live) setVerdict(v); }).catch(() => {});
    return () => { live = false; };
  }, [space, offeringId, JSON.stringify(picks), qty]);
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
            await orderForCustomer(id, it.offering, space ? picks : null, space?.fungible ? qty : 1);
            setDone(`Ordered on the customer's behalf: ${it.offering.name}${space?.fungible && qty > 1 ? ` ×${qty}` : ''}`);
            setWhat(null);
          }, 'new');
        }}>
          <span className="dim small">With the customer's say-so on the line</span>
          <select value={offeringId} onChange={(e) => setOfferingId(e.target.value)} aria-label="Offering" data-testid="new-order-offering">
            <option value="">Pick an offering…</option>
            {suggestions.map((s) => <option key={s.offering.id} value={s.offering.id}>{s.offering.name}</option>)}
          </select>
          {space && (space.configurationCharacteristic || []).map((c) => {
            const range = (c.productSpecCharacteristicValue || []).find((v) => v.value == null && (v.valueFrom != null || v.valueTo != null));
            return (
              <label key={c.name} className="small" data-testid={`new-order-pick-${c.name}`}>{c.name}{' '}
                {range ? (
                  <input type="number" min={range.valueFrom ?? undefined} max={range.valueTo ?? undefined} value={picks[c.name] ?? (range.valueFrom ?? 0)} onChange={(e) => setPicks((p) => ({ ...p, [c.name]: e.target.value }))} />
                ) : (
                  <select value={picks[c.name] || ''} onChange={(e) => setPicks((p) => ({ ...p, [c.name]: e.target.value }))}>
                    {(c.productSpecCharacteristicValue || []).map((v) => <option key={v.value} value={v.value} disabled={v.isSelectable === false}>{v.value}{v.isSelectable === false ? ' — sold out' : ''}</option>)}
                  </select>
                )}
              </label>
            );
          })}
          {space?.fungible && <label className="small" data-testid="new-order-quantity">how many <input type="number" min="1" value={qty} onChange={(e) => setQty(Math.max(1, parseInt(e.target.value || '1', 10)))} /></label>}
          {verdict && verdict.state === 'accepted' && <span className="dim small" data-testid="new-order-price">{Number(verdict.configurationPrice?.monthlyTotal?.value || 0).toFixed(2)} {verdict.configurationPrice?.monthlyTotal?.unit}/month{Number(verdict.configurationPrice?.oneTimeTotal?.value || 0) > 0 ? ` + ${Number(verdict.configurationPrice.oneTimeTotal.value).toFixed(2)} once` : ''}</span>}
          {verdict && verdict.state === 'rejected' && <span className="error small" data-testid="new-order-rejected">{(verdict.message || []).join(' · ')}</span>}
          <button className="primary" type="submit" disabled={!offeringId || (verdict && verdict.state === 'rejected')}>Order now</button>
          <button className="ghost" type="button" onClick={close}>Cancel</button>
        </form>
      )}
    </div>
  );
}
