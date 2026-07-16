/*
 * OIDC authorization-code flow with PKCE, hand-rolled and provider-agnostic.
 * Works against any spec-compliant issuer; defaults target the bundled dev
 * Keycloak. Override by defining window.BSS_BIZ_CONFIG before this script.
 */
'use strict';

const AUTH_CONFIG = Object.assign({
  issuer: 'http://localhost:8085/realms/bss',
  clientId: 'bss-biz',
  scope: 'openid',
}, window.BSS_BIZ_CONFIG || {});

const TOKEN_KEY = 'bss.biz.token';
const EXP_KEY = 'bss.biz.tokenExp';
const VERIFIER_KEY = 'bss.biz.verifier';
const STATE_KEY = 'bss.biz.state';

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
  return location.origin + location.pathname;
}

async function beginLogin() {
  const verifier = randomString();
  const state = randomString();
  sessionStorage.setItem(VERIFIER_KEY, verifier);
  sessionStorage.setItem(STATE_KEY, state);
  const challenge = b64url(await sha256(verifier));
  const q = new URLSearchParams({
    client_id: AUTH_CONFIG.clientId,
    redirect_uri: redirectUri(),
    response_type: 'code',
    scope: AUTH_CONFIG.scope,
    state: state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
  });
  location.assign(AUTH_CONFIG.issuer + '/protocol/openid-connect/auth?' + q);
}

async function completeLogin(code) {
  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    client_id: AUTH_CONFIG.clientId,
    redirect_uri: redirectUri(),
    code: code,
    code_verifier: sessionStorage.getItem(VERIFIER_KEY) || '',
  });
  const res = await fetch(AUTH_CONFIG.issuer + '/protocol/openid-connect/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: body,
  });
  if (!res.ok) {
    throw new Error('token exchange failed: HTTP ' + res.status);
  }
  const tokens = await res.json();
  sessionStorage.setItem(TOKEN_KEY, tokens.access_token);
  sessionStorage.setItem(EXP_KEY, String(Date.now() + (tokens.expires_in - 15) * 1000));
  sessionStorage.removeItem(VERIFIER_KEY);
  sessionStorage.removeItem(STATE_KEY);
  history.replaceState(null, '', redirectUri());
}

function currentToken() {
  const exp = Number(sessionStorage.getItem(EXP_KEY) || 0);
  if (Date.now() >= exp) {
    return null;
  }
  return sessionStorage.getItem(TOKEN_KEY);
}

function tokenClaims() {
  const token = currentToken();
  if (!token) return {};
  try {
    return JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
  } catch (e) {
    return {};
  }
}

function hasRole(role) {
  return ((tokenClaims().realm_access || {}).roles || []).includes(role);
}

function signOut() {
  sessionStorage.removeItem(TOKEN_KEY);
  sessionStorage.removeItem(EXP_KEY);
  location.assign(AUTH_CONFIG.issuer + '/protocol/openid-connect/logout?' + new URLSearchParams({
    client_id: AUTH_CONFIG.clientId,
    post_logout_redirect_uri: redirectUri(),
  }));
}

/** Fetch with the bearer token; a 401 restarts login (token expired/revoked). */
async function authFetch(url, options) {
  const token = currentToken();
  if (!token) {
    await beginLogin();
    return new Promise(() => {}); // navigation takes over
  }
  const opts = Object.assign({}, options);
  opts.headers = Object.assign({}, opts.headers, { Authorization: 'Bearer ' + token });
  const res = await fetch(url, opts);
  if (res.status === 401) {
    sessionStorage.removeItem(TOKEN_KEY);
    await beginLogin();
    return new Promise(() => {});
  }
  return res;
}

/** Resolves true when authenticated (handling the redirect leg if present). */
async function ensureSignedIn() {
  const params = new URLSearchParams(location.search);
  if (params.has('code')) {
    if (params.get('state') !== sessionStorage.getItem(STATE_KEY)) {
      throw new Error('OIDC state mismatch');
    }
    await completeLogin(params.get('code'));
    return true;
  }
  if (currentToken()) {
    return true;
  }
  await beginLogin();
  return false;
}
