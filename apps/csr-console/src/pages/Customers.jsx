import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { searchCustomers, customerByNumber, customerByIdentifier } from '../api.js';
import { desk } from '../desk.js';

/* Universal customer search: a name, an email, a phone number, or any id the
 * caller reads out — a customer id, an order, a ticket, a product — resolved to
 * the person behind it. Rows carry enough to pick the right customer without
 * opening them; the customers this agent opened last are one click away. */

const looksLikeNumber = (s) => /^\+?[\d\s-]{6,}$/.test(s.trim());
const looksLikeId = (s) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(s.trim()) || /^[0-9a-f]{32}$/i.test(s.trim());
const RECENT_KEY = 'bss.csr.recent';

export function rememberRecent(customer) {
  try {
    const list = JSON.parse(localStorage.getItem(RECENT_KEY) || '[]').filter((c) => c.id !== customer.id);
    list.unshift({ id: customer.id, name: `${customer.givenName || ''} ${customer.familyName || ''}`.trim(), at: Date.now() });
    localStorage.setItem(RECENT_KEY, JSON.stringify(list.slice(0, 6)));
  } catch { /* storage may be unavailable */ }
}

const medium = (c, type) => (c.contactMedium || []).filter((m) => m.mediumType === type && m.characteristic?.source !== 'folkeregisteret');
const emailOf = (c) => medium(c, 'email')[0]?.characteristic?.emailAddress;
const phonesOf = (c) => medium(c, 'phone').map((m) => m.characteristic?.phoneNumber).filter(Boolean);
const cityOf = (c) => medium(c, 'postalAddress')[0]?.characteristic?.city;

export default function Customers() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState(null);
  const [resolvedVia, setResolvedVia] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [recent, setRecent] = useState(() => { try { return JSON.parse(localStorage.getItem(RECENT_KEY) || '[]'); } catch { return []; } });
  const seq = useRef(0);

  const search = async (q) => {
    const mine = ++seq.current;
    setBusy(true); setError(null); setResolvedVia(null);
    try {
      let hits;
      if (q.trim() && looksLikeId(q)) {
        const r = await customerByIdentifier(q);
        hits = r ? [r.customer] : [];
        if (r) setResolvedVia(r.via);
      } else if (q.trim() && looksLikeNumber(q)) {
        const c = await customerByNumber(q.trim());
        hits = c ? [c] : [];
        if (c) setResolvedVia('phone number');
      } else {
        hits = await searchCustomers(q.trim());
      }
      if (mine !== seq.current) return;
      setResults(hits);
      if (q.trim() && !hits.length) desk('search.empty', 'customers', { query: q.trim().slice(0, 60) });
    } catch (e) {
      if (mine === seq.current) setError(e.message);
    } finally {
      if (mine === seq.current) setBusy(false);
    }
  };

  useEffect(() => {
    const t = setTimeout(() => search(query), query ? 300 : 0);
    return () => clearTimeout(t);
  }, [query]);

  return (
    <>
      <h1>Customers</h1>
      <form className="searchbar" onSubmit={(e) => { e.preventDefault(); search(query); }}>
        <input name="q" placeholder="Name, email, phone number — or any id: customer, order, ticket, product…" value={query}
               data-testid="cust-search" aria-label="Search customers" onChange={(e) => setQuery(e.target.value)} />
        <button className="primary" type="submit">{busy ? 'Searching…' : 'Search'}</button>
      </form>
      {!query && recent.length > 0 && (
        <p className="dim small recent" data-testid="recent-customers">
          Recent: {recent.map((r) => <Link key={r.id} className="chip" to={`/customer/${r.id}`}>{r.name || r.id.slice(0, 8)}</Link>)}
        </p>
      )}
      {error && <p className="error">{error}</p>}
      {resolvedVia && <p className="dim small" data-testid="resolved-via">Found by {resolvedVia}.</p>}
      {!results ? <p className="dim">Loading…</p> : !results.length ? (
        <p className="dim">No customers found.{looksLikeId(query) ? ' Not a customer, order, ticket or product id this desk can see.' : ''}</p>
      ) : (
        <div className="rows">
          {results.map((c) => {
            const email = emailOf(c); const phones = phonesOf(c); const city = cityOf(c);
            return (
              <Link className="row rowlink" key={c.id} to={`/customer/${c.id}`} onClick={() => rememberRecent(c)}>
                <span>
                  <strong>{c.givenName} {c.familyName}</strong>
                  <span className="dim small rowmeta">
                    {[email, ...phones.slice(0, 2), city].filter(Boolean).join(' · ') || <span title={c.id}>{c.id.slice(0, 8)}…</span>}
                  </span>
                </span>
                <span className="dim small">
                  {c.deceased === true ? <span className="state cancelled">deceased</span> : null}
                  {c.addressProtected === true ? <span className="state cancelled">protected address</span> : null}
                  {(c.contactMedium || []).some((m) => m.characteristic?.source === 'folkeregisteret') ? <span className="state active">✓ registered</span> : null}
                </span>
              </Link>
            );
          })}
        </div>
      )}
    </>
  );
}
