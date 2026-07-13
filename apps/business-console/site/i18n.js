/*
 * Tenant-driven i18n for the business console — same idea as the storefront:
 * the gateway's tenant manifest says the operator's locale and currency;
 * English strings are the keys, untranslated strings pass through. Static
 * markup opts in with data-i18n attributes, translated at boot.
 */
'use strict';

const I18N_CFG = window.BSS_BIZ_CONFIG || {};
const LOCALE = I18N_CFG.locale || 'en';
const CURRENCY = I18N_CFG.currency || 'EUR';
const INTL_LOCALE = { no: 'nb-NO', en: 'en-IE' }[LOCALE] || LOCALE;

const I18N_NO = {
  'BUSINESS': 'MIN BEDRIFT',
  'Business console': 'Min bedrift',
  'Sign out': 'Logg ut',
  'Your organization:': 'Din organisasjon:',
  'People & their lines': 'Personer og deres linjer',
  'First name': 'Fornavn',
  'Last name': 'Etternavn',
  'Email': 'E-post',
  'Add person': 'Legg til person',
  'Plans & your company pricing': 'Abonnementer og bedriftspriser',
  'Order a subscription for someone': 'Bestill abonnement til noen',
  'Order': 'Bestill',
  "Change someone's plan": 'Bytt abonnement for noen',
  'Same line, same number — only the plan changes.': 'Samme linje, samme nummer — bare abonnementet endres.',
  'Change plan': 'Bytt abonnement',
  'Company invoices': 'Firmafakturaer',
  'Who…': 'Hvem…',
  'Their line…': 'Deres linje…',
  'New plan…': 'Nytt abonnement…',
  'My work line at': 'Min jobblinje hos',
  'My services': 'Mine tjenester',
  'My usage': 'Mitt forbruk',
  'Billing': 'Fakturering',
  'Need help?': 'Trenger du hjelp?',
  'Show PUK': 'Vis PUK',
  'Reset PIN': 'Tilbakestill PIN',
  'New PIN': 'Ny PIN',
  'month': 'md.',
  'no lines yet': 'ingen linjer ennå',
  'line': 'linje',
  'lines': 'linjer',
  'adding…': 'legger til…',
  'ordering…': 'bestiller…',
  'changing…': 'bytter…',
  '✓ plan changed — same number, new plan': '✓ abonnement byttet — samme nummer, nytt abonnement',
  '✓ ordered — the line activates in seconds': '✓ bestilt — linjen aktiveres om sekunder',
  '✓ sent to your SIM': '✓ sendt til SIM-kortet ditt',
  'Nobody yet — add your first person below.': 'Ingen ennå — legg til den første personen nedenfor.',
  'No invoices yet — they appear after the operator\'s billing run.':
    'Ingen fakturaer ennå — de kommer etter operatørens faktureringskjøring.',
  'No priced plans in the catalog.': 'Ingen prisede abonnementer i katalogen.',
  'No lines yet — your company admin can order one for you.':
    'Ingen linjer ennå — bedriftsadministratoren din kan bestille en til deg.',
  'No usage yet.': 'Ingen forbruk ennå.',
  'Your subscription is paid by your company — charges appear on':
    'Abonnementet ditt betales av bedriften din — kostnadene havner på',
  "your organization's consolidated invoice. Anything you buy yourself, and any device cost above the company allowance, appears below on your personal bill.":
    'bedriftens samlefaktura. Alt du kjøper selv, og enhver enhetskostnad over bedriftens ramme, vises nedenfor på din personlige faktura.',
  'Company policy': 'Bedriftspolicy',
  'Device allowance: the company pays a device\'s monthly charge up to this amount — anything above it goes on the employee\'s personal bill.':
    'Enhetsramme: bedriften betaler enhetens månedskostnad opp til dette beløpet — alt over havner på den ansattes personlige faktura.',
  'Amount per month': 'Beløp per måned',
  'Save policy': 'Lagre policy',
  'saving…': 'lagrer…',
  'enter an amount': 'oppgi et beløp',
  '✓ saved — applies from the next billing run': '✓ lagret — gjelder fra neste faktureringskjøring',
  '✓ allowance removed — the company pays devices in full': '✓ ramme fjernet — bedriften betaler enheter fullt ut',
  "Contact your company admin for plan changes, or the operator's customer care for service issues.":
    'Kontakt bedriftsadministratoren for abonnementsendringer, eller operatørens kundeservice ved tjenesteproblemer.',
};

const I18N_BUNDLES = { no: I18N_NO };

function t(s) {
  return (I18N_BUNDLES[LOCALE] || {})[s] || s;
}

/** "kr 299,00" in Norway; English keeps "299.00 EUR". */
function fmtMoney(value, unit) {
  if (LOCALE === 'en') return `${Number(value).toFixed(2)} ${unit || CURRENCY}`;
  try {
    return new Intl.NumberFormat(INTL_LOCALE, { style: 'currency', currency: unit || CURRENCY })
      .format(value);
  } catch {
    return `${Number(value).toFixed(2)} ${unit || CURRENCY}`;
  }
}

/** Translate static markup: <el data-i18n> swaps its text, placeholders too. */
document.addEventListener('DOMContentLoaded', () => {
  document.title = t(document.title);
  for (const el of document.querySelectorAll('[data-i18n]')) {
    el.textContent = t(el.textContent.replace(/\s+/g, ' ').trim());
  }
  for (const el of document.querySelectorAll('[data-i18n-placeholder]')) {
    el.placeholder = t(el.placeholder);
  }
});
