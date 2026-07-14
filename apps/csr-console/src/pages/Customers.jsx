import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { customerByNumber, searchCustomers } from '../api.js';

/**
 * The agent's front door, search-as-you-type: any fragment of a name, an
 * email, an address bit — or a PHONE NUMBER, resolved in the tenant's own
 * pool. Results refresh 300ms after the last keystroke; out-of-order
 * responses are dropped so fast typing never shows stale hits.
 */
export default function Customers() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const seq = useRef(0);

  const looksLikeNumber = (s) => /^\+?[\d\s-]{6,}$/.test(s.trim());

  const search = async (q) => {
    const mySeq = ++seq.current;
    setBusy(true);
    try {
      let hits;
      if (looksLikeNumber(q)) {
        const owner = await customerByNumber(q.trim());
        hits = owner ? [owner] : [];
      } else {
        hits = await searchCustomers(q.trim());
      }
      if (mySeq === seq.current) { setResults(hits); setError(null); }
    } catch (e) {
      if (mySeq === seq.current) setError(e.message);
    }
    if (mySeq === seq.current) setBusy(false);
  };

  // type-ahead: settle 300ms after the last keystroke
  useEffect(() => {
    const t = setTimeout(() => { search(query); }, query ? 300 : 0);
    return () => clearTimeout(t);
  }, [query]);

  return (
    <>
      <h1>Customers</h1>
      <form className="searchbar" onSubmit={(e) => { e.preventDefault(); search(query); }}>
        <input name="q" placeholder="Name, email or phone number…" value={query}
               data-testid="cust-search" onChange={(e) => setQuery(e.target.value)} />
        <button className="primary" type="submit">{busy ? 'Searching…' : 'Search'}</button>
      </form>
      {error && <p className="error">{error}</p>}
      {!results ? <p className="dim">Loading…</p> : !results.length ? <p className="dim">No customers found.</p> : (
        <div className="rows">
          {results.map((c) => (
            <Link className="row rowlink" key={c.id} to={`/customer/${c.id}`}>
              <strong>{c.givenName} {c.familyName}</strong>
              <span className="dim small">
                {(c.contactMedium || []).find((m) => m.mediumType === 'email')?.characteristic?.emailAddress
                  || <span title={c.id}>{c.id.slice(0, 8)}…</span>}
              </span>
            </Link>
          ))}
        </div>
      )}
    </>
  );
}
