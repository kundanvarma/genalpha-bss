import { useEffect, useState } from 'react';
import { NavLink, Route, Routes } from 'react-router-dom';
import { ensureSignedIn, signOut, tokenClaims } from './auth.js';
import Customers from './pages/Customers.jsx';
import Customer360 from './pages/Customer360.jsx';
import Tickets from './pages/Tickets.jsx';
import Stock from './pages/Stock.jsx';

export default function App() {
  const [state, setState] = useState('signing-in');
  const [error, setError] = useState(null);

  useEffect(() => {
    ensureSignedIn()
      .then((ready) => { if (ready) setState('ready'); })
      .catch((e) => { setError(e.message); setState('error'); });
  }, []);

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
          <span className="mark">GenAlpha</span>
          <span className="area">csr console</span>
          {claims.org && <span className="orgbadge">{claims.org}</span>}
        </div>
        <nav className="nav">
          <NavLink to="/" end>Customers</NavLink>
          <NavLink to="/tickets">Tickets</NavLink>
          <NavLink to="/stock">Stock</NavLink>
        </nav>
        <div className="who">
          <span className="user">{claims.name || claims.preferred_username || ''}</span>
          <button className="ghost" onClick={signOut}>Sign out</button>
        </div>
      </header>
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
