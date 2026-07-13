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
  logoUrl: '/tmf-api/documentManagement/v4/document/brand-logo',
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

/** "299,00 kr" for a Norwegian tenant, "12.99 EUR" style kept for English. */
export function money(value, unit) {
  const { locale = 'en', currency = 'EUR' } = config;
  if (locale === 'en') return `${Number(value).toFixed(2)} ${unit || currency}`;
  try {
    return new Intl.NumberFormat({ no: 'nb-NO' }[locale] || locale,
      { style: 'currency', currency: unit || currency }).format(value);
  } catch {
    return `${Number(value).toFixed(2)} ${unit || currency}`;
  }
}
