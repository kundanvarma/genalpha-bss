/*
 * OIDC authorization-code flow with PKCE, hand-rolled and provider-agnostic —
 * the same flow the admin console uses, with the storefront client. Keycloak's
 * login page carries the self-registration link (registrationAllowed).
 */

import { config } from './config.js';

// Read when used, not when imported: this module is in the server bundle's
// graph, and `window` does not exist there (#180).
const authConfig = () => Object.assign({
  issuer: 'http://localhost:8085/realms/bss',
  clientId: 'bss-storefront',
  scope: 'openid profile email',
}, config());

/*
 * THE SESSION STORE, OR NOTHING AT ALL (#180).
 *
 * A server render has no sessionStorage and no session, and those are the same
 * fact: a crawler is a guest, and the guest view is the correct document to
 * render for one. Reading the session through here rather than touching the
 * global directly is what makes that true instead of a crash — `tokenClaims()`
 * runs on every render of every page, so a bare `sessionStorage.getItem` in it
 * takes down the whole server-rendered shop.
 *
 * NOT a memory fallback. One process serves every request, so a module-scoped
 * store would hand one visitor's token to the next — this reads as empty and
 * discards writes, which is exactly what a server with no browser should do.
 * The browser path is untouched: there, `sessionStorage` exists and is used.
 *
 * This was a real outage, caught by a deployment rather than by the suite that
 * claimed to cover it: Node 22 has no Web Storage, Node 24 and later expose
 * `sessionStorage`, and the laptop was running the later one. The suite now
 * removes the globals before it renders so the laptop reproduces the image.
 */
const NO_SESSION = {
  getItem: () => null,
  setItem: () => {},
  removeItem: () => {},
};
const session = () => (typeof sessionStorage === 'undefined' ? NO_SESSION : sessionStorage);

const TOKEN_KEY = 'bss.shop.token';
const REFRESH_KEY = 'bss.shop.refresh';
const EXP_KEY = 'bss.shop.tokenExp';
const VERIFIER_KEY = 'bss.shop.verifier';
const STATE_KEY = 'bss.shop.state';
const RETURN_KEY = 'bss.shop.returnTo';

function b64url(bytes) {
  return btoa(String.fromCharCode(...new Uint8Array(bytes)))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function randomString() {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return b64url(bytes);
}

async function sha256(text) {
  return crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
}

function redirectUri() {
  return location.origin + '/shop/';
}

export async function beginLogin() {
  // The registered redirect URI is always /shop/, so a deep link (a payer
  // opening /shop/family/{id} in a fresh tab) would be lost across the
  // provider bounce — remember it here, restore it after the token exchange.
  const here = location.pathname.replace(/^\/shop\/?/, '/') + location.search + location.hash;
  if (here !== '/') {
    session().setItem(RETURN_KEY, here);
  }
  const verifier = randomString();
  const state = randomString();
  session().setItem(VERIFIER_KEY, verifier);
  session().setItem(STATE_KEY, state);
  const challenge = b64url(await sha256(verifier));
  const q = new URLSearchParams({
    client_id: authConfig().clientId,
    redirect_uri: redirectUri(),
    response_type: 'code',
    scope: authConfig().scope,
    state: state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
  });
  // "Switch account" asks for credentials instead of silently reusing an
  // active single-sign-on session (e.g. a staff console session).
  if (session().getItem('bss.shop.forceLogin')) {
    session().removeItem('bss.shop.forceLogin');
    q.set('prompt', 'login');
  }
  location.assign(authConfig().issuer + '/protocol/openid-connect/auth?' + q);
}

async function completeLogin(code) {
  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    client_id: authConfig().clientId,
    redirect_uri: redirectUri(),
    code: code,
    code_verifier: session().getItem(VERIFIER_KEY) || '',
  });
  const res = await fetch(authConfig().issuer + '/protocol/openid-connect/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: body,
  });
  if (!res.ok) {
    throw new Error('token exchange failed: HTTP ' + res.status);
  }
  const tokens = await res.json();
  storeTokens(tokens);
  session().removeItem(VERIFIER_KEY);
  session().removeItem(STATE_KEY);
  history.replaceState(null, '', redirectUri());
}

function storeTokens(tokens) {
  session().setItem(TOKEN_KEY, tokens.access_token);
  session().setItem(EXP_KEY, String(Date.now() + (tokens.expires_in - 15) * 1000));
  if (tokens.refresh_token) {
    session().setItem(REFRESH_KEY, tokens.refresh_token);
  }
}

/**
 * Silent renewal, single-flight: WITHOUT this, several parallel authFetch
 * calls on an idle page each start their own login redirect, their PKCE
 * verifiers race, and the customer lands on an error page — the exact bug
 * the household suite caught on a page left open past token expiry.
 */
let refreshing = null;
async function tryRefresh() {
  if (refreshing) return refreshing;
  const refreshToken = session().getItem(REFRESH_KEY);
  if (!refreshToken) return false;
  refreshing = (async () => {
    try {
      const res = await fetch(authConfig().issuer + '/protocol/openid-connect/token', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
          grant_type: 'refresh_token',
          client_id: authConfig().clientId,
          refresh_token: refreshToken,
        }),
      });
      if (!res.ok) {
        session().removeItem(REFRESH_KEY);
        return false;
      }
      storeTokens(await res.json());
      return true;
    } catch (e) {
      return false;
    } finally {
      refreshing = null;
    }
  })();
  return refreshing;
}

function currentToken() {
  const exp = Number(session().getItem(EXP_KEY) || 0);
  if (Date.now() >= exp) {
    return null;
  }
  return session().getItem(TOKEN_KEY);
}

export function tokenClaims() {
  const token = currentToken();
  if (!token) return {};
  try {
    return JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
  } catch {
    return {};
  }
}

/** Is the signed-in identity a real SHOPPER? A customer has a party_id (the
 * TMF632 individual behind them) or the baseline customer role. A pure STAFF
 * account (a console superuser like demo) has neither — same-realm SSO can
 * carry such a session into the shop, and it must NOT be presented as a
 * ready-to-shop customer. */
export function isCustomer() {
  const c = tokenClaims();
  const roles = (c.realm_access || {}).roles || [];
  return Boolean(c.party_id) || roles.includes('customer');
}

/** Sign out and re-authenticate WITH a prompt — the "switch account" escape for
 * a staff session that leaked into the shop. */
export function switchAccount() {
  session().setItem('bss.shop.forceLogin', '1');
  signOut();
}

export function signOut() {
  session().removeItem(TOKEN_KEY);
  session().removeItem(REFRESH_KEY);
  session().removeItem(EXP_KEY);
  location.assign(authConfig().issuer + '/protocol/openid-connect/logout?' + new URLSearchParams({
    client_id: authConfig().clientId,
    post_logout_redirect_uri: redirectUri(),
  }));
}

export function isSignedIn() {
  return currentToken() != null;
}

/** Fetch with the bearer token; expiry refreshes SILENTLY and single-flight —
 * the login redirect is the last resort, never the first answer to a 401. */
export async function authFetch(url, options) {
  // the sales channel this front end sells through — the catalog enforces
  // an offer's channel list server-side, this header only says who is asking
  options = { ...(options || {}), headers: { 'X-Channel': 'web', ...((options && options.headers) || {}) } };
  let token = currentToken();
  if (!token) {
    if (await tryRefresh()) {
      token = session().getItem(TOKEN_KEY);
    } else {
      await beginLogin();
      return new Promise(() => {}); // navigation takes over
    }
  }
  const call = (bearer) => {
    const opts = Object.assign({}, options);
    opts.headers = Object.assign({}, opts.headers, { Authorization: 'Bearer ' + bearer });
    return fetch(url, opts);
  };
  let res = await call(token);
  if (res.status === 401) {
    if (await tryRefresh()) {
      res = await call(session().getItem(TOKEN_KEY));
      if (res.status !== 401) return res;
    }
    session().removeItem(TOKEN_KEY);
    await beginLogin();
    return new Promise(() => {});
  }
  return res;
}

/**
 * Fetch for anonymous-readable resources (the catalog): sends the token when
 * one exists, but never forces a login — guests browse before they register.
 */
export async function publicFetch(url, options) {
  const token = currentToken();
  if (!token) {
    return fetch(url, options);
  }
  const opts = Object.assign({}, options);
  opts.headers = Object.assign({}, opts.headers, { Authorization: 'Bearer ' + token });
  return fetch(url, opts);
}

/**
 * Completes the OIDC redirect leg if this load is one; resolves the deep-link
 * path saved before the bounce (or true) when a login just finished. Never
 * initiates a login — checkout does that.
 */
export async function handleCallback() {
  const params = new URLSearchParams(location.search);
  if (!params.has('code')) {
    return false;
  }
  if (params.get('state') !== session().getItem(STATE_KEY)) {
    throw new Error('OIDC state mismatch');
  }
  await completeLogin(params.get('code'));
  const returnTo = session().getItem(RETURN_KEY);
  session().removeItem(RETURN_KEY);
  return returnTo || true;
}
