import { useEffect, useState } from 'react';
import { NavLink, Route, Routes, useNavigate } from 'react-router-dom';
import { beginLogin, handleCallback, isSignedIn, signOut, tokenClaims } from './auth.js';
import { ensureParty } from './api.js';
import { CART_EVENT, cartCount, cartLines, clearCart } from './cart.js';
import { PAYMENT_REQUIRED, performCheckout } from './checkout.js';
import { takePendingCheckout } from './pending.js';
import Shop from './pages/Shop.jsx';
import Offering from './pages/Offering.jsx';
import Cart from './pages/Cart.jsx';
import Orders from './pages/Orders.jsx';
import Bills from './pages/Bills.jsx';
import Support from './pages/Support.jsx';
import Services from './pages/Services.jsx';
import Account from './pages/Account.jsx';

export default function App() {
  const [state, setState] = useState('boot'); // boot | guest | ready | error
  const [error, setError] = useState(null);
  const [count, setCount] = useState(cartCount());
  const navigate = useNavigate();

  useEffect(() => {
    const refresh = () => setCount(cartCount());
    window.addEventListener(CART_EVENT, refresh);
    return () => window.removeEventListener(CART_EVENT, refresh);
  }, []);

  useEffect(() => {
    (async () => {
      try {
        await handleCallback(); // completes the redirect leg, if this is one
        if (!isSignedIn()) {
          setState('guest');
          return;
        }
        await ensureParty();
        // Checkout started as a guest? The cart survived in localStorage —
        // place the order they were building.
        if (takePendingCheckout() && cartLines().length) {
          try {
            await performCheckout(cartLines());
            clearCart();
            navigate('/orders');
          } catch (e) {
            if (e.message !== PAYMENT_REQUIRED) throw e;
            // Card details never survive a redirect: back to the cart, now
            // signed in, to confirm payment.
            navigate('/cart');
          }
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
          <NavLink to="/cart" className="cartlink">
            Cart{count > 0 && <span className="badge">{count}</span>}
          </NavLink>
          <NavLink to="/orders">My orders</NavLink>
          <NavLink to="/bills">My bills</NavLink>
          <NavLink to="/services">My services</NavLink>
          <NavLink to="/support">Support</NavLink>
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
          <Route path="/cart" element={<Cart />} />
          <Route path="/orders" element={<Orders />} />
          <Route path="/bills" element={<Bills />} />
          <Route path="/services" element={<Services />} />
          <Route path="/support" element={<Support />} />
          <Route path="/account" element={<Account />} />
        </Routes>
      </main>
    </>
  );
}
