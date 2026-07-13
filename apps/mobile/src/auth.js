/*
 * OIDC authorization-code + PKCE, both targets. Web: hand-rolled against
 * the tenant's issuer (same proven flow as the storefront). Native: the
 * promised phase two — expo-auth-session drives the system browser via the
 * genalphabss:// deep link, and the token lives in memory for the session.
 * The rest of the app only ever calls getToken()/isSignedIn()/beginLogin().
 */
import { Platform } from 'react-native';
import { tenantConfig } from './config.js';

let nativeToken = null; // native only: in-session token

const TOKEN_KEY = 'bss.app.token';
const VERIFIER_KEY = 'bss.app.verifier';
const STATE_KEY = 'bss.app.state';

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
  return window.location.origin + '/app/';
}

export function getToken() {
  return Platform.OS === 'web' ? sessionStorage.getItem(TOKEN_KEY) : nativeToken;
}

export function isSignedIn() {
  return Boolean(getToken());
}

export function tokenClaims() {
  const token = getToken();
  if (!token) return {};
  try {
    return JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
  } catch {
    return {};
  }
}

/** Native: full round trip through the system browser; resolves signed-in. */
async function beginLoginNative() {
  const { issuer, clientId } = tenantConfig();
  const AuthSession = require('expo-auth-session');
  const discovery = {
    authorizationEndpoint: issuer + '/protocol/openid-connect/auth',
    tokenEndpoint: issuer + '/protocol/openid-connect/token',
  };
  const redirectUri = AuthSession.makeRedirectUri({ scheme: 'genalphabss' });
  const request = new AuthSession.AuthRequest({
    clientId,
    scopes: ['openid', 'profile', 'email'],
    redirectUri,
    usePKCE: true,
  });
  const result = await request.promptAsync(discovery);
  if (result.type !== 'success') return false;
  const tokens = await AuthSession.exchangeCodeAsync({
    clientId,
    code: result.params.code,
    redirectUri,
    extraParams: { code_verifier: request.codeVerifier },
  }, discovery);
  nativeToken = tokens.accessToken;
  return true;
}

export async function beginLogin() {
  if (Platform.OS !== 'web') return beginLoginNative();
  const { issuer, clientId } = tenantConfig();
  const verifier = randomString();
  const state = randomString();
  sessionStorage.setItem(VERIFIER_KEY, verifier);
  sessionStorage.setItem(STATE_KEY, state);
  const challenge = b64url(await sha256(verifier));
  const q = new URLSearchParams({
    client_id: clientId,
    response_type: 'code',
    scope: 'openid profile email',
    redirect_uri: redirectUri(),
    state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
  });
  window.location.assign(issuer + '/protocol/openid-connect/auth?' + q);
}

/** Handles ?code= on app boot; true when a token is (now) present. */
export async function completeLogin() {
  if (Platform.OS !== 'web') return false;
  const params = new URLSearchParams(window.location.search);
  const code = params.get('code');
  if (!code) return isSignedIn();
  if (params.get('state') !== sessionStorage.getItem(STATE_KEY)) return false;
  const { issuer, clientId } = tenantConfig();
  const res = await fetch(issuer + '/protocol/openid-connect/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: clientId,
      code,
      redirect_uri: redirectUri(),
      code_verifier: sessionStorage.getItem(VERIFIER_KEY),
    }),
  });
  if (!res.ok) return false;
  const body = await res.json();
  sessionStorage.setItem(TOKEN_KEY, body.access_token);
  window.history.replaceState({}, '', redirectUri());
  return true;
}

export function signOut() {
  if (Platform.OS !== 'web') {
    nativeToken = null;
    return;
  }
  sessionStorage.removeItem(TOKEN_KEY);
  const { issuer, clientId } = tenantConfig();
  window.location.assign(issuer + '/protocol/openid-connect/logout?' + new URLSearchParams({
    client_id: clientId,
    post_logout_redirect_uri: redirectUri(),
  }));
}
