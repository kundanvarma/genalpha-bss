/*
 * Tenant-driven i18n: the gateway's tenant manifest says which locale and
 * currency this operator runs in (GenAlpha: en/EUR, Nova: no/NOK) — one
 * build, every language. English strings ARE the keys, so an untranslated
 * string falls back to itself and the English tenant pays zero cost.
 */
const CFG = window.BSS_STOREFRONT_CONFIG || {};

export const locale = CFG.locale || 'en';
export const currency = CFG.currency || 'EUR';

/** BCP-47 tag for Intl formatting. */
export const intlLocale = { no: 'nb-NO', en: 'en-IE' }[locale] || locale;

const NO = {
  // nav + identity
  'Offers': 'Tilbud',
  'Cart': 'Handlekurv',
  'My orders': 'Mine bestillinger',
  'My bills': 'Mine fakturaer',
  'My page': 'Min side',
  'Inbox': 'Innboks',
  'Support': 'Kundeservice',
  'Account': 'Konto',
  'Sign in': 'Logg inn',
  'Sign out': 'Logg ut',
  // my page cards
  'My bundle': 'Min pakke',
  'Mobile': 'Mobil',
  'Broadband': 'Bredbånd',
  'TV & entertainment': 'TV og underholdning',
  'My devices': 'Mine enheter',
  'Also active': 'Også aktivt',
  "This month's usage": 'Forbruk denne måneden',
  'Complete your setup': 'Fullfør oppsettet',
  'Get started': 'Kom i gang',
  'Add a mobile plan': 'Legg til mobilabonnement',
  'Add broadband': 'Legg til bredbånd',
  'Add TV & streaming': 'Legg til TV og strømming',
  'Your number:': 'Ditt nummer:',
  'My SIM': 'Mitt SIM-kort',
  'Show PUK': 'Vis PUK',
  'Reset PIN': 'Tilbakestill PIN',
  'New PIN': 'Ny PIN',
  'Change plan': 'Bytt abonnement',
  'New plan…': 'Nytt abonnement…',
  'Confirm': 'Bekreft',
  'Cancel': 'Avbryt',
  'Changing…': 'Bytter…',
  'Buying…': 'Kjøper…',
  'Loading your page…': 'Laster siden din…',
  'No plan yet — pick one in the shop.': 'Ingen abonnement ennå — velg ett i butikken.',
  'Your line appears here once the plan activates.': 'Linjen din vises her når abonnementet aktiveres.',
  'active': 'aktiv',
  // commerce
  'Add to cart': 'Legg i handlekurven',
  'About this device': 'Om denne enheten',
  "private customers only; company purchases don't qualify": 'kun privatkunder; bedriftskjøp kvalifiserer ikke',
  'for business customers': 'for bedriftskunder',
  'view the partner product': 'se partnerproduktet',
  'add the deal to cart': 'legg tilbudet i handlekurven',
  'Add': 'Legg til',
  'My household': 'Min husstand',
  'Paid for by': 'Betales av',
  'company-style: their bill, your name on the lines': 'som bedrift: deres faktura, ditt navn på linjene',
  'Waiting for': 'Venter på at',
  'to accept your request': 'godtar forespørselen din',
  'Leave': 'Forlat',
  'who pays for you? their email': 'hvem betaler for deg? deres e-post',
  'Ask them to pay': 'Be dem betale',
  'request sent — pending their consent': 'forespørsel sendt — venter på samtykke',
  'asks you to pay for them': 'ber deg betale for seg',
  'Accept': 'Godta',
  'accepted — their orders can bill to you now': 'godtatt — deres bestillinger kan faktureres deg',
  'order a plan for them…': 'bestill abonnement til dem…',
  'Order': 'Bestill',
  'ordered — it bills to you, attributed to them': 'bestilt — faktureres deg, i deres navn',
  'Stop paying': 'Slutt å betale',
  'stopped paying': 'sluttet å betale',
  'left the household': 'forlot husstanden',
  'One person, many payers: ask someone to pay for your subscriptions, or accept requests from family here.':
    'Én person, mange betalere: be noen betale for abonnementene dine, eller godta familieforespørsler her.',
  'Indicative price per month': 'Veiledende pris per måned',
  'Your price per month': 'Din pris per måned',
  'Public deals only — sign in for your exact price; business purchases may price differently.':
    'Kun offentlige tilbud — logg inn for din eksakte pris; bedriftskjøp kan prises annerledes.',
  'Checkout': 'Til kassen',
  'Total': 'Totalt',
  'due now': 'å betale nå',
  'per month': 'per måned',
  'month': 'md.',
  'one-time': 'engangs',
  'Pay': 'Betal',
  'from': 'fra',
  'Out of stock': 'Utsolgt',
  'Latest bill': 'Siste faktura',
  'My subscriptions & protection': 'Mine abonnementer og beskyttelse',
  'activation code:': 'aktiveringskode:',
  'manage with the partner': 'administreres hos partneren',
  'protecting every line on this account': 'beskytter alle linjer på kontoen',
};

const BUNDLES = { no: NO };

/** Translate a UI string; unknown strings pass through in English. */
export function t(s) {
  return (BUNDLES[locale] || {})[s] || s;
}

/** "kr 299,00" in Norway, "€29.99" in Ireland — from the price's own unit. */
export function money(value, unit) {
  try {
    return new Intl.NumberFormat(intlLocale, {
      style: 'currency', currency: unit || currency,
    }).format(value);
  } catch {
    return `${Number(value).toFixed(2)} ${unit || currency}`;
  }
}
