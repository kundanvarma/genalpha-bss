import { useEffect, useState } from 'react';
import { Link, NavLink, Route, Routes, useNavigate } from 'react-router-dom';
import ChatWidget from './ChatWidget.jsx';
import HelpDrawer from './HelpDrawer.jsx';
import { t } from './i18n.js';
import { beginLogin, handleCallback, isCustomer, isSignedIn, signOut, switchAccount, tokenClaims } from './auth.js';
import { ensureParty, myNotifications, stitchVisitor } from './api.js';
import { CART_EVENT, cartCount, cartLines, claimCart, markCartCheckedOut } from './cart.js';
import { PAYMENT_REQUIRED, performCheckout } from './checkout.js';
import { takePendingCheckout } from './pending.js';
import Shop from './pages/Shop.jsx';
import Offering from './pages/Offering.jsx';
import FamilyMember from './pages/FamilyMember.jsx';
import Family from './pages/Family.jsx';
import Cart from './pages/Cart.jsx';
import Orders from './pages/Orders.jsx';
import Bills from './pages/Bills.jsx';
import Support from './pages/Support.jsx';
import Notifications from './pages/Notifications.jsx';
import Services from './pages/Services.jsx';
import Account from './pages/Account.jsx';
import Devices from './pages/Devices.jsx';

export default function App() {
  const [state, setState] = useState('boot'); // boot | guest | ready | error
  const [error, setError] = useState(null);
  const [count, setCount] = useState(0);
  const [unread, setUnread] = useState(0);
  const navigate = useNavigate();

  useEffect(() => {
    const refresh = () => { cartCount().then(setCount).catch(() => {}); };
    refresh();
    window.addEventListener(CART_EVENT, refresh);
    return () => window.removeEventListener(CART_EVENT, refresh);
  }, []);

  useEffect(() => {
    (async () => {
      try {
        const returnTo = await handleCallback(); // completes the redirect leg, if this is one
        if (!isSignedIn()) {
          setState('guest');
          return;
        }
        await ensureParty();
        // the login stitch: this browser's insight profile belongs to this
        // customer now — only ever under personalization consent
        stitchVisitor();
        await claimCart();
        myNotifications()
          .then((ms) => setUnread(ms.filter((m) => m.status !== 'read').length))
          .catch(() => {});
        // Checkout started as a guest? The cart survived in localStorage —
        // place the order they were building.
        const pendingLines = takePendingCheckout() ? await cartLines() : [];
        if (pendingLines.length) {
          try {
            const order = await performCheckout(pendingLines);
            await markCartCheckedOut(order.id);
            navigate('/orders');
          } catch (e) {
            if (e.code === 'CREDIT_FROZEN') {
              // The frozen-credit story belongs on the cart page, where the
              // prepaid alternative is one link away.
              localStorage.setItem('bss.shop.creditFrozen', '1');
              navigate('/cart');
            } else if (e.message !== PAYMENT_REQUIRED) {
              // The order they were building could not be placed (an address
              // the operator does not serve, a slot just taken…). That is the
              // cart's story to tell — the sign-in itself succeeded.
              localStorage.setItem('bss.shop.checkoutError', e.message);
              navigate('/cart');
            } else {
              // Card details never survive a redirect: back to the cart, now
              // signed in, to confirm payment.
              navigate('/cart');
            }
          }
        } else if (typeof returnTo === 'string') {
          // The router still sits on the redirect landing page — send the
          // customer back to the deep link they signed in for.
          navigate(returnTo, { replace: true });
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
  // A signed-in identity that is NOT a shopper (a staff session carried in by
  // SSO) gets the shop as a GUEST plus a switch prompt — never the customer
  // account UI, and never a checkout under a non-customer identity.
  const customer = state === 'ready' && isCustomer();
  const staffInShop = state === 'ready' && !isCustomer();
  return (
    <>
      <header className="top">
        <div className="brand">
          <img className="brandlogo" src={window.BSS_STOREFRONT_CONFIG?.logoUrl || '/tmf-api/documentManagement/v4/document/brand-logo'} alt="" onError={(e) => { e.currentTarget.style.display = 'none'; }} />
          <span className="area">shop</span>
        </div>
        <nav className="nav">
          <NavLink to="/" end>{t('Offers')}</NavLink>
          <NavLink to="/cart" className="cartlink">
            {t('Cart')}{count > 0 && <span className="badge">{count}</span>}
          </NavLink>
          {customer && (
            <>
              <NavLink to="/orders">{t('My orders')}</NavLink>
              <NavLink to="/bills">{t('My bills')}</NavLink>
              <NavLink to="/services">{t('My page')}</NavLink>
              <NavLink to="/devices">{t('My devices')}</NavLink>
              <NavLink to="/family">{t('Family')}</NavLink>
              <NavLink to="/notifications" className="cartlink">
                {t('Inbox')}{unread > 0 && <span className="badge">{unread}</span>}
              </NavLink>
            </>
          )}
          <NavLink to="/support">{t('Support')}</NavLink>
          {customer && <NavLink to="/account">{t('Account')}</NavLink>}
        </nav>
        <div className="who">
          <HelpDrawer />
          {customer ? (
            <>
              <span className="avatar" data-testid="avatar">{(claims.given_name?.[0] || claims.preferred_username?.[0] || '?').toUpperCase()}{(claims.family_name?.[0] || '').toUpperCase()}</span>
              <span className="user">{claims.name || claims.preferred_username || ''}</span>
              <button className="ghost" onClick={signOut}>{t('Sign out')}</button>
            </>
          ) : staffInShop ? (
            <button className="primary" data-testid="switch-account" onClick={switchAccount}>Switch to a customer account</button>
          ) : (
            <button className="primary" onClick={beginLogin}>{t('Sign in')}</button>
          )}
        </div>
      </header>
      {staffInShop && (
        <div className="staffbanner" data-testid="staff-in-shop">
          You're signed in as <b>{claims.name || claims.preferred_username}</b>, a staff account — not a
          shopping account. Browse freely, but to buy or manage a subscription, switch to a customer account.
        </div>
      )}
      <main>
        <Routes>
          <Route path="/" element={<Shop />} />
          <Route path="/offering/:id" element={<Offering />} />
          <Route path="/family" element={<Family />} />
          <Route path="/family/:id" element={<FamilyMember />} />
          <Route path="/cart" element={<Cart />} />
          <Route path="/orders" element={<Orders />} />
          <Route path="/bills" element={<Bills />} />
          <Route path="/services" element={<Services />} />
          <Route path="/devices" element={<Devices />} />
          <Route path="/notifications" element={<Notifications />} />
          <Route path="/support" element={<Support />} />
          <Route path="/account" element={<Account />} />
        </Routes>
      </main>
      <SiteFooter />
      <ChatWidget />
    </>
  );
}

/** Every operator site ends the same way — how to reach us, where the app is,
 * the legal pages. All of it comes from the tenant manifest, none from code. */
function SiteFooter() {
  const cfg = window.BSS_STOREFRONT_CONFIG || {};
  const wa = cfg.supportWhatsapp ? `https://wa.me/${cfg.supportWhatsapp.replace(/[^0-9]/g, '')}` : null;
  const tel = cfg.supportPhone ? `tel:${cfg.supportPhone.replace(/[^0-9+]/g, '')}` : null;
  const any = wa || tel || cfg.supportEmail || cfg.appStoreUrl || cfg.playStoreUrl || cfg.privacyUrl || cfg.termsUrl;
  if (!any) return null;
  return (
    <footer className="sitefooter" data-testid="site-footer">
      <div className="footcol">
        <strong>{cfg.brandName || ''}</strong>
        {cfg.tagline && <span className="dim">{cfg.tagline}</span>}
      </div>
      <div className="footcol">
        <strong>{t('Talk to us')}</strong>
        {wa && <a href={wa} target="_blank" rel="noopener">💬 {t('WhatsApp')} {cfg.supportWhatsapp}</a>}
        {tel && <a href={tel}>📞 {cfg.supportPhone}</a>}
        {cfg.supportEmail && <a href={`mailto:${cfg.supportEmail}`}>✉️ {cfg.supportEmail}</a>}
        <Link to="/support">{t('Support & FAQ')}</Link>
      </div>
      {(cfg.appStoreUrl || cfg.playStoreUrl) && (
        <div className="footcol">
          <strong>{t('The app')}</strong>
          {cfg.appStoreUrl && <a href={cfg.appStoreUrl} target="_blank" rel="noopener"> App Store</a>}
          {cfg.playStoreUrl && <a href={cfg.playStoreUrl} target="_blank" rel="noopener">▶ Google Play</a>}
        </div>
      )}
      <div className="footcol">
        <strong>{t('Legal')}</strong>
        {cfg.privacyUrl && <a href={cfg.privacyUrl} target="_blank" rel="noopener">{t('Privacy policy')}</a>}
        {cfg.termsUrl && <a href={cfg.termsUrl} target="_blank" rel="noopener">{t('Terms and conditions')}</a>}
        {cfg.priceNote && <span className="dim small">{cfg.priceNote}</span>}
        <span className="dim small">© {new Date().getFullYear()} {cfg.brandName || ''}</span>
      </div>
    </footer>
  );
}
