import { useEffect, useState } from 'react';
import { NavLink, Route, Routes, useNavigate } from 'react-router-dom';
import { beginLogin, handleCallback, isSignedIn, signOut, tokenClaims } from './auth.js';
import { ensureParty, getOffering, placeOrder } from './api.js';
import { PENDING_OFFER_KEY } from './pending.js';
import Shop from './pages/Shop.jsx';
import Offering from './pages/Offering.jsx';
import Orders from './pages/Orders.jsx';
import Services from './pages/Services.jsx';
import Account from './pages/Account.jsx';

export default function App() {
  const [state, setState] = useState('boot'); // boot | guest | ready | error
  const [error, setError] = useState(null);
  const navigate = useNavigate();

  useEffect(() => {
    (async () => {
      try {
        await handleCallback(); // completes the redirect leg, if this is one
        if (!isSignedIn()) {
          setState('guest');
          return;
        }
        await ensureParty();
        // Checkout started as a guest? Finish the interrupted order now.
        const pendingOffer = sessionStorage.getItem(PENDING_OFFER_KEY);
        if (pendingOffer) {
          sessionStorage.removeItem(PENDING_OFFER_KEY);
          await placeOrder(await getOffering(pendingOffer));
          navigate('/orders');
        }
        setState('ready');
      } catch (e) {
        setError(e.message);
        setState('error');
      }
    })();
  }, []);

  if (state === 'boot') {
    return <div className="gatepost">Loading…</div>;
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
          {state === 'ready' ? (
            <>
              <span className="user">{claims.name || claims.preferred_username || ''}</span>
              <button className="ghost" onClick={signOut}>Sign out</button>
            </>
          ) : (
            <button className="primary" onClick={beginLogin}>Sign in</button>
          )}
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
