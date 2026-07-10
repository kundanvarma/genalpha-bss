import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { searchCustomers } from '../api.js';

export default function Customers() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState(null);
  const [error, setError] = useState(null);

  const search = (name) => searchCustomers(name).then(setResults).catch((e) => setError(e.message));
  useEffect(() => { search(''); }, []);

  return (
    <>
      <h1>Customers</h1>
      <form className="searchbar" onSubmit={(e) => { e.preventDefault(); search(query.trim()); }}>
        <input name="q" placeholder="Family name…" value={query}
               onChange={(e) => setQuery(e.target.value)} />
        <button className="primary" type="submit">Search</button>
      </form>
      {error && <p className="error">{error}</p>}
      {!results ? <p className="dim">Loading…</p> : !results.length ? <p className="dim">No customers found.</p> : (
        <div className="rows">
          {results.map((c) => (
            <Link className="row rowlink" key={c.id} to={`/customer/${c.id}`}>
              <strong>{c.givenName} {c.familyName}</strong>
              <span className="dim small">{c.id}</span>
            </Link>
          ))}
        </div>
      )}
    </>
  );
}
