import { useEffect, useState } from 'react';
import { NavLink, Route, Routes } from 'react-router-dom';
import { ensureSignedIn, signOut, tokenClaims } from './auth.js';
import { ensureParty } from './api.js';
import Shop from './pages/Shop.jsx';
import Offering from './pages/Offering.jsx';
import Orders from './pages/Orders.jsx';
import Services from './pages/Services.jsx';
import Account from './pages/Account.jsx';

export default function App() {
  const [state, setState] = useState('signing-in'); // signing-in | ready | error
  const [error, setError] = useState(null);

  useEffect(() => {
    (async () => {
      try {
        const ready = await ensureSignedIn();
        if (!ready) return; // redirect in flight
        await ensureParty();
        setState('ready');
      } catch (e) {
        setError(e.message);
        setState('error');
      }
    })();
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
          <span className="area">shop</span>
        </div>
        <nav className="nav">
          <NavLink to="/" end>Offers</NavLink>
          <NavLink to="/orders">My orders</NavLink>
          <NavLink to="/services">My services</NavLink>
          <NavLink to="/account">Account</NavLink>
        </nav>
        <div className="who">
          <span className="user">{claims.name || claims.preferred_username || ''}</span>
          <button className="ghost" onClick={signOut}>Sign out</button>
        </div>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<Shop />} />
          <Route path="/offering/:id" element={<Offering />} />
          <Route path="/orders" element={<Orders />} />
          <Route path="/services" element={<Services />} />
          <Route path="/account" element={<Account />} />
        </Routes>
      </main>
    </>
  );
}
