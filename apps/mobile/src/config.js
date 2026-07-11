/*
 * Tenant manifest: which operator this install serves, which IdP to log in
 * against, and the branding tokens. Served by the gateway per hostname —
 * the same white-label mechanism as the web channels, JSON-shaped for the app.
 */
const DEFAULTS = {
  tenantId: 'genalpha',
  issuer: 'http://localhost:8085/realms/bss',
  clientId: 'bss-app',
  brandName: 'MyGenAlpha',
  brandColor: '#0E7C7B',
};

let config = { ...DEFAULTS };

export async function loadTenantConfig() {
  try {
    const res = await fetch('/app/tenant-config.json');
    if (res.ok) config = { ...DEFAULTS, ...(await res.json()) };
  } catch {
    // gateway not reachable (e.g. bare dev server): defaults apply
  }
  return config;
}

export function tenantConfig() {
  return config;
}
