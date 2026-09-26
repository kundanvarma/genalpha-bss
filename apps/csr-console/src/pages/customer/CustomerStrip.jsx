import { useEffect, useState } from 'react';
import { logInteraction, verifyPartyAddress } from '../../api.js';
import { due } from './situation.jsx';

/* CSR-UX-004 (#148): the customer context anchor.
 *
 * The workspace's top bar is sticky; the customer header was not, so the one
 * thing an agent must never lose — WHICH customer this is — scrolled away while
 * they took consequential actions on services, bills and orders. This strip is
 * sticky directly under the top bar and carries only what a decision needs:
 * who, what kind of customer, what state they are in, how to reach them,
 * whether their identity has been checked on this call, and two numbers that
 * change what an agent does next (running services, money outstanding).
 *
 * Everything deeper stays where it was — under Services, Billing & account and
 * More. A strip that grows into a summary page stops being an anchor.
 *
 * Sticky needs one thing to be true: the strip must be a direct child of a
 * container as tall as the page. It is rendered straight into <main>, and the
 * notices come after it in the same fragment rather than wrapped with it — a
 * wrapper div would end the sticky run where the wrapper ends.
 */

const party = (id) => [{ id, role: 'customer', '@referredType': 'Individual' }];
const CHECK_KEY = (id) => `bss.csr.identitycheck.${id}`;

/* What an agent can actually check on a call, in their own words. Nothing here
 * claims an electronic identity: BankID/eID is a seam that does not exist yet
 * (docs/freg — follow-up), so it is not offered as if it did. */
const CHECKS = [
  'date of birth',
  'registered address',
  'a one-time code to the registered number',
  'the security question on file',
];

const readCheck = (id) => {
  try { return JSON.parse(sessionStorage.getItem(CHECK_KEY(id)) || 'null'); } catch { return null; }
};

/** The customer's state in the words a desk uses, from what the record actually says. */
export function customerStatus({ customer, activeServices, rowCount }) {
  if (customer.deceased === true) return { word: 'deceased', level: 'warn' };
  const running = activeServices.filter((s) => s.state === 'active').length;
  if (running) return { word: 'active', level: 'ok' };
  if (activeServices.length) return { word: 'all services paused', level: 'warn' };
  if (rowCount) return { word: 'no running service', level: 'warn' };
  return { word: 'no services yet', level: 'dim' };
}

/** Consumer or business, from the party record — never a guess from the name. */
export const customerKind = (customer) => (customer.organization && (customer.organization.name || customer.organization.id)
  ? { word: 'Business', detail: customer.organization.name || null }
  : { word: 'Consumer', detail: null });

/** The identity check an agent did on this call: state first, then how to change it. */
function IdentityCheck({ id, customer, act }) {
  const [check, setCheck] = useState(() => readCheck(id));
  const [asking, setAsking] = useState(false);
  const [how, setHow] = useState(CHECKS[0]);
  useEffect(() => { setCheck(readCheck(id)); setAsking(false); }, [id]);

  if (check) {
    return (
      <span className="strip-item">
        <span className="chip ok" data-testid="identity-state" data-identity="verified">
          ✓ identity verified
        </span>
        <span className="dim small" data-testid="identity-how">{check.how} · {new Date(check.at).toLocaleTimeString()}</span>
        <button className="linkish small" data-testid="identity-clear"
          title="The caller changed, or the check no longer holds — put the strip back to unverified"
          onClick={() => { sessionStorage.removeItem(CHECK_KEY(id)); setCheck(null); }}>not this caller</button>
      </span>
    );
  }
  return (
    <span className="strip-item">
      <span className="chip warn" data-testid="identity-state" data-identity="unverified">
        ! identity not verified
      </span>
      {!asking ? (
        <button className="ghost small" data-testid="identity-verify"
          title="Check who you are speaking to before disclosing a PUK, changing a plan or moving money"
          onClick={() => setAsking(true)}>Verify caller</button>
      ) : (
        <>
          <select className="small" data-testid="identity-how-pick" aria-label="What was checked"
            value={how} onChange={(e) => setHow(e.target.value)}>
            {CHECKS.map((c) => <option key={c} value={c}>{c}</option>)}
          </select>
          <button className="primary small" data-testid="identity-confirm"
            onClick={() => {
              const rec = { how, at: new Date().toISOString() };
              sessionStorage.setItem(CHECK_KEY(id), JSON.stringify(rec));
              setCheck(rec); setAsking(false);
              // the check belongs on the record, not only on this screen
              act(() => logInteraction({
                description: `Caller identity verified by ${how}`,
                channel: 'phone', direction: 'inbound', sourceSystem: 'csr-console', relatedParty: party(id),
              }), 'identity');
            }}>Confirm</button>
          <button className="linkish small" data-testid="identity-cancel" onClick={() => setAsking(false)}>Cancel</button>
        </>
      )}
    </span>
  );
}

export default function CustomerStrip({ id, customer, act, email, numbers, address, registered,
  addressProtected, showableAddress, activeServices, openBills, rowCount, onArea }) {
  // The strip sticks BELOW the sticky top bar, so it has to know how tall that
  // bar is — measured, never a constant: the bar wraps on a narrow desk.
  const [chrome, setChrome] = useState(52);
  useEffect(() => {
    const bar = document.querySelector('header.top');
    if (!bar) return undefined;
    const measure = () => setChrome(Math.round(bar.getBoundingClientRect().height));
    measure();
    if (typeof ResizeObserver !== 'function') return undefined;
    const ro = new ResizeObserver(measure); ro.observe(bar);
    return () => ro.disconnect();
  }, []);

  const [regCheck, setRegCheck] = useState(null);
  useEffect(() => { setRegCheck(null); }, [id]);

  const status = customerStatus({ customer, activeServices, rowCount });
  const kind = customerKind(customer);
  const running = activeServices.filter((s) => s.state === 'active').length;
  const owed = openBills.reduce((s, b) => s + due(b), 0);
  const unit = openBills.length ? (openBills[0].amountDue?.unit || '') : '';

  return (
    <>
      <div className="cust-strip" data-testid="cust-strip" style={{ top: chrome }}>
        <div className="strip-who">
          <span className="avatar big">{(customer.givenName?.[0] || '?').toUpperCase()}{(customer.familyName?.[0] || '').toUpperCase()}</span>
          <div>
            {/* the page's heading IS the customer — an <h1> here keeps that true for
                a screen reader and for every suite that finds a customer by it */}
            <h1 className="strip-name" data-testid="strip-name">{customer.givenName} {customer.familyName}</h1>
            <span className="dim small strip-ref" data-testid="strip-ref" title={customer.id}>
              {kind.word}{kind.detail ? ` · ${kind.detail}` : ''} · ref {customer.id.slice(0, 8)}
            </span>
          </div>
        </div>

        <span className={`chip ${status.level === 'warn' ? 'warn' : status.level === 'ok' ? 'ok' : ''}`} data-testid="strip-status">{status.word}</span>

        <IdentityCheck id={id} customer={customer} act={act} />

        <span className="strip-item dim small" data-testid="strip-contact">
          {email || <span title={customer.id}>no email on file</span>}
          {numbers.length > 0 && <> · <span data-testid="cust-numbers">
            {numbers.slice(0, 2).map((n) => <span key={n} className="msisdn" style={{ marginRight: 6 }}>{n}</span>)}
            {numbers.length > 2 && <span className="dim">+{numbers.length - 2} under Services</span>}
          </span></>}
          {showableAddress && <> · {showableAddress.street1}, {showableAddress.postCode} {showableAddress.city}</>}
          {!addressProtected && registered && <span className="ok" data-testid="registered-hint"> · ✓ registered address</span>}
          {showableAddress && (
            <> · <button className="linkish small" data-testid="reverify-address" disabled={regCheck === 'checking'}
              onClick={async () => {
                setRegCheck('checking');
                try { const m = await verifyPartyAddress(customer, address); setRegCheck(m ? m.outcome : 'unavailable'); } catch { setRegCheck('unavailable'); }
              }}>{regCheck === 'checking' ? 'Checking register…' : 'Re-verify address'}</button>
            {regCheck && regCheck !== 'checking' && (
              <span data-testid="reverify-result" className={regCheck === 'match' ? 'ok' : 'dim'}>
                {' '}{regCheck === 'match' ? '✓ matches the national register' : regCheck === 'unavailable' ? 'no registry for this market' : 'could not be verified'}
              </span>
            )}</>
          )}
        </span>

        <span className="strip-numbers">
          <button className="strip-metric" data-testid="strip-services" onClick={() => onArea('services')}
            title="Services running right now — the whole inventory is under Services">
            <strong>{running}</strong> <span className="dim small">active service{running === 1 ? '' : 's'}</span>
          </button>
          <button className={`strip-metric${openBills.length ? ' warn' : ''}`} data-testid="strip-balance" onClick={() => onArea('billing')}
            title="What the customer still owes — the bills themselves are under Billing & account">
            {openBills.length
              ? <><strong>{owed.toFixed(2)} {unit}</strong> <span className="dim small">outstanding</span></>
              : <span className="dim small">nothing outstanding</span>}
          </button>
        </span>
      </div>

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
    </>
  );
}
