import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { desk } from '../desk.js';
import Results, { moveFocus } from './search/Results.jsx';
import { queryKind, resolveQuery, withHoldings } from './search/typedSearch.js';

/* The desk's one search box (#147). A name, an email, a phone number, or any
 * reference the caller reads out — a customer, an order, a ticket, a
 * subscription, a device agreement. What comes back is grouped by what it IS,
 * so the agent knows what they are about to open; the row model and the
 * lookups live in ./search/. The customers this agent opened last are one
 * click away. */

const RECENT_KEY = 'bss.csr.recent';

export function rememberRecent(customer) {
  try {
    const list = JSON.parse(localStorage.getItem(RECENT_KEY) || '[]').filter((c) => c.id !== customer.id);
    list.unshift({ id: customer.id, name: `${customer.givenName || ''} ${customer.familyName || ''}`.trim(), at: Date.now() });
    localStorage.setItem(RECENT_KEY, JSON.stringify(list.slice(0, 6)));
  } catch { /* storage may be unavailable */ }
}

/* Why a query found nothing, in the agent's words rather than a shrug. Free
 * text reaches customers only: no component offers a text search over orders,
 * tickets or offerings, so those are found by their reference. Saying so is
 * the honest version of an empty list. */
const NOTHING = {
  reference: 'No customer, order, ticket, subscription or device agreement carries that reference.',
  number: 'Nobody in this tenant holds that number.',
  text: 'No customer matches that name or email. Orders, tickets, subscriptions and devices are found by the reference the caller reads out — paste one in.',
};

export default function Customers() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState(null);
  const [kind, setKind] = useState('empty');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [recent, setRecent] = useState(() => { try { return JSON.parse(localStorage.getItem(RECENT_KEY) || '[]'); } catch { return []; } });
  // the query the rows on screen actually answer — the box carries it as
  // data-settled, so a proof run waits for THIS query's results instead of
  // guessing from a spinner that blinks twice while the caret moves
  const [settled, setSettled] = useState(null);
  const seq = useRef(0);
  const listRef = useRef(null);

  const search = async (q) => {
    const mine = ++seq.current;
    setBusy(true); setError(null); setKind(queryKind(q)); setSettled(null);
    try {
      const hits = await resolveQuery(q);
      if (mine !== seq.current) return;
      setResults(hits);
      if (q.trim() && !hits.length) desk('search.empty', 'customers', { query: q.trim().slice(0, 60) });
      // the holding count is a second round trip: the rows identify somebody
      // the moment they render, and gain their subscription count after.
      const enriched = await withHoldings(hits);
      if (mine !== seq.current) return;
      setResults(enriched);
      setSettled(q);
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

  // recents are written by whoever opens a customer; re-read them on return
  useEffect(() => {
    try { setRecent(JSON.parse(localStorage.getItem(RECENT_KEY) || '[]')); } catch { /* unavailable */ }
  }, []);

  return (
    <>
      <h1>Search</h1>
      <form className="searchbar" data-settled={settled === null ? undefined : settled}
            onSubmit={(e) => { e.preventDefault(); search(query); }}>
        <input name="q" placeholder="Search customers, orders, tickets, subscriptions…" value={query}
               data-testid="cust-search" aria-label="Search customers, orders, tickets, subscriptions"
               aria-describedby="search-hint"
               onChange={(e) => setQuery(e.target.value)}
               onKeyDown={(e) => {
                 if (e.key === 'ArrowDown') { e.preventDefault(); moveFocus(listRef.current, null, 0); }
               }} />
        <button className="primary" type="submit">{busy ? 'Searching…' : 'Search'}</button>
      </form>
      <p className="dim small" id="search-hint" data-testid="search-hint">
        A name or an email finds customers. A phone number finds the line and who holds it. Any
        reference — customer, order, ticket, subscription, device agreement — finds that object.
        Results are grouped by what they are; ↓ walks them, Enter opens.
      </p>
      {!query && recent.length > 0 && (
        <p className="dim small recent" data-testid="recent-customers">
          Recent: {recent.map((r) => <Link key={r.id} className="chip" to={`/customer/${r.id}`}>{r.name || r.id.slice(0, 8)}</Link>)}
        </p>
      )}
      {error && <p className="error">{error}</p>}
      {!results ? <p className="dim">Loading…</p> : !results.length ? (
        <p className="dim" data-testid="search-nothing">{query ? NOTHING[kind] || NOTHING.text : 'Nothing found.'}</p>
      ) : <Results results={results} listRef={listRef} />}
    </>
  );
}
