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
const INTL_LOCALE = { no: 'nb-NO', nn: 'nn-NO', en: 'en-IE' }[LOCALE] || LOCALE;

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
  'From whom…': 'Fra hvem…',
  'To whom…': 'Til hvem…',
  'Reassign line': 'Flytt linje',
  'Line…': 'Linje…',
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

/* Nynorsk: inherits bokmål; only differing forms overridden. */
const I18N_NN = {
  ...I18N_NO,
  'Business console': 'Mi bedrift',
  'BUSINESS': 'MI BEDRIFT',
  'People & their lines': 'Personar og linjene deira',
  'First name': 'Fornamn',
  'Last name': 'Etternamn',
  'Plans & your company pricing': 'Abonnement og bedriftsprisar',
  'Order a subscription for someone': 'Bestill abonnement til nokon',
  "Change someone's plan": 'Byt abonnement for nokon',
  'Same line, same number — only the plan changes.': 'Same linje, same nummer — berre abonnementet vert endra.',
  'Change plan': 'Byt abonnement',
  'Company invoices': 'Firmafakturaar',
  'Who…': 'Kven…',
  'From whom…': 'Frå kven…',
  'To whom…': 'Til kven…',
  'Their line…': 'Linja deira…',
  'My work line at': 'Mi jobblinje hos',
  'My services': 'Mine tenester',
  'Need help?': 'Treng du hjelp?',
  'no lines yet': 'ingen linjer enno',
  'adding…': 'legg til…',
  'ordering…': 'bestiller…',
  'changing…': 'byter…',
  '✓ plan changed — same number, new plan': '✓ abonnement bytt — same nummer, nytt abonnement',
  '✓ ordered — the line activates in seconds': '✓ bestilt — linja vert aktivert om sekund',
  '✓ sent to your SIM': '✓ sendt til SIM-kortet ditt',
  'Nobody yet — add your first person below.': 'Ingen enno — legg til den første personen nedanfor.',
  'No invoices yet — they appear after the operator\'s billing run.':
    'Ingen fakturaar enno — dei kjem etter faktureringskøyringa til operatøren.',
  'No priced plans in the catalog.': 'Ingen prisa abonnement i katalogen.',
  'No lines yet — your company admin can order one for you.':
    'Ingen linjer enno — bedriftsadministratoren din kan bestille ei til deg.',
  'No usage yet.': 'Ingen forbruk enno.',
  'Your subscription is paid by your company — charges appear on':
    'Abonnementet ditt vert betalt av bedrifta di — kostnadene hamnar på',
  "your organization's consolidated invoice. Anything you buy yourself, and any device cost above the company allowance, appears below on your personal bill.":
    'samlefakturaen til bedrifta. Alt du kjøper sjølv, og einingskostnader over ramma til bedrifta, vert vist nedanfor på din personlege faktura.',
  'Device allowance: the company pays a device\'s monthly charge up to this amount — anything above it goes on the employee\'s personal bill.':
    'Einingsramme: bedrifta betalar månadskostnaden for eininga opp til dette beløpet — alt over hamnar på den personlege fakturaen til den tilsette.',
  'Amount per month': 'Beløp per månad',
  'saving…': 'lagrar…',
  'enter an amount': 'oppgje eit beløp',
  '✓ saved — applies from the next billing run': '✓ lagra — gjeld frå neste faktureringskøyring',
  '✓ allowance removed — the company pays devices in full': '✓ ramme fjerna — bedrifta betalar einingar fullt ut',
  "Contact your company admin for plan changes, or the operator's customer care for service issues.":
    'Kontakt bedriftsadministratoren for abonnementsendringar, eller kundeservicen til operatøren ved tenesteproblem.',
};

const I18N_BUNDLES = { no: I18N_NO, nn: I18N_NN };

function t(s) {
  return (I18N_BUNDLES[LOCALE] || {})[s] || s;
}

/** "kr 299,00" in Norway; English keeps "299.00 EUR". */
const TENANT_COUNTRY = (window.BSS_BIZ_CONFIG || {}).country || '';
const PRICE_DECIMALS = (window.BSS_BIZ_CONFIG || {}).priceDecimals;
function fmtMoney(value, unit) {
  // English without a country keeps "299.00 EUR"; a tenant that names its country formats its way
  if (LOCALE === 'en' && !TENANT_COUNTRY) return `${Number(value).toFixed(2)} ${unit || CURRENCY}`;
  try {
    return new Intl.NumberFormat(TENANT_COUNTRY ? `${LOCALE === 'no' ? 'nb' : LOCALE}-${TENANT_COUNTRY}` : INTL_LOCALE,
      { style: 'currency', currency: unit || CURRENCY,
        ...(Number.isInteger(PRICE_DECIMALS) ? { minimumFractionDigits: PRICE_DECIMALS, maximumFractionDigits: PRICE_DECIMALS } : {}) })
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
