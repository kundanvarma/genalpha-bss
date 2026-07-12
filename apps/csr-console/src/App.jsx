import { useEffect, useState } from 'react';
import { NavLink, Route, Routes } from 'react-router-dom';
import { openProblems } from './api.js';
import { ensureSignedIn, signOut, tokenClaims, hasRole } from './auth.js';
import Customers from './pages/Customers.jsx';
import Customer360 from './pages/Customer360.jsx';
import Tickets from './pages/Tickets.jsx';
import Stock from './pages/Stock.jsx';

export default function App() {
  const [state, setState] = useState('signing-in');
  const [error, setError] = useState(null);
  const [problems, setProblems] = useState([]);

  useEffect(() => {
    ensureSignedIn()
      .then((ready) => { if (ready) setState('ready'); })
      .catch((e) => { setError(e.message); setState('error'); });
  }, []);

  // TMF656: agents see live outages the moment they exist.
  useEffect(() => {
    if (state !== 'ready') return undefined;
    const poll = () => { openProblems().then(setProblems); };
    poll();
    const timer = setInterval(poll, 30000);
    return () => clearInterval(timer);
  }, [state]);

  if (state === 'signing-in') {
    return <div className="gatepost">Signing in with your identity provider…</div>;
  }
  if (state === 'error') {
    return <div className="gatepost error">Sign-in failed: {error}</div>;
  }

  const claims = tokenClaims();
  return (
    <>
      <header className="top">
        <div className="brand">
          <img className="brandlogo" src={window.BSS_CSR_CONFIG?.logoUrl || '/tmf-api/documentManagement/v4/document/brand-logo'} alt="" onError={(e) => { e.currentTarget.style.display = 'none'; }} />
          <span className="area">csr console</span>
          {claims.org && <span className="orgbadge">{claims.org}</span>}
        </div>
        <nav className="nav">
          <NavLink to="/" end>Customers</NavLink>
          <NavLink to="/tickets">Tickets</NavLink>
          {hasRole('stock:read') && <NavLink to="/stock">Stock</NavLink>}
        </nav>
        <div className="who">
          <span className="avatar" data-testid="avatar">{(claims.given_name?.[0] || claims.preferred_username?.[0] || '?').toUpperCase()}{(claims.family_name?.[0] || '').toUpperCase()}</span>
          <span className="user">{claims.name || claims.preferred_username || ''}</span>
          <button className="ghost" onClick={signOut}>Sign out</button>
        </div>
      </header>
      {problems.length > 0 && (
        <div className="outagebanner" data-testid="outage-banner">
          ⚠ {problems.map((p) => p.name).join(' · ')} — customers in the affected
          area may report degraded service.
        </div>
      )}
      <main className="wide">
        <Routes>
          <Route path="/" element={<Customers />} />
          <Route path="/customer/:id" element={<Customer360 />} />
          <Route path="/tickets" element={<Tickets />} />
          <Route path="/stock" element={<Stock />} />
        </Routes>
      </main>
    </>
  );
}
