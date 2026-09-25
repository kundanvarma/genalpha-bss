/*
 * Tenant-driven i18n: the gateway's tenant manifest says which locale and
 * currency this operator runs in (GenAlpha: en/EUR, Nova: no/NOK) — one
 * build, every language. English strings ARE the keys, so an untranslated
 * string falls back to itself and the English tenant pays zero cost.
 */
const CFG = window.BSS_STOREFRONT_CONFIG || {};

export const locale = CFG.locale || 'en';
export const currency = CFG.currency || 'EUR';

/** ISO country the operator sells in — drives address defaults, number hints, Intl locale. */
export const country = CFG.country || '';

/** BCP-47 tag for Intl formatting: the tenant's language + its country when it says one
 * (en-GY, nb-NO), else the historical defaults. */
const LANG = { no: 'nb', nn: 'nn', en: 'en' };
export const intlLocale = country
  ? `${LANG[locale] || locale}-${country}`
  : ({ no: 'nb-NO', nn: 'nn-NO', en: 'en-IE' }[locale] || locale);

/** Price formatting the operator's way: minor-unit digits (Guyana shows whole dollars) and
 * how the currency is written ($ vs GY$ vs GYD). */
export const priceFormat = {
  ...(Number.isInteger(CFG.priceDecimals) ? { minimumFractionDigits: CFG.priceDecimals, maximumFractionDigits: CFG.priceDecimals } : {}),
  ...(CFG.currencyDisplay ? { currencyDisplay: CFG.currencyDisplay } : {}),
};
/** A statutory note shown beside prices ("14% VAT included"), when the operator sets one. */
export const priceNote = CFG.priceNote || '';

import { NO_SHOP } from './i18n/no.js';
import { NO_ACCOUNT } from './i18n/no-account.js';
const NO = { ...NO_SHOP, ...NO_ACCOUNT };
import { NN } from './i18n/nn.js';

const BUNDLES = { no: NO, nn: NN };

/** Translate a UI string; unknown strings pass through in English. */
export function t(s) {
  return (BUNDLES[locale] || {})[s] || s;
}

/** "kr 299,00" in Norway, "€29.99" in Ireland — from the price's own unit. */
export function money(value, unit) {
  try {
    return new Intl.NumberFormat(intlLocale, {
      style: 'currency', currency: unit || currency, ...priceFormat,
    }).format(value);
  } catch {
    return `${Number(value).toFixed(2)} ${unit || currency}`;
  }
}
