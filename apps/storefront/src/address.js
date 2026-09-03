/*
 * Shipping address helpers. The saved address lives on the TMF632 individual
 * as a postalAddress contactMedium; on orders it rides each physical item as
 * a TMF622 place (GeographicAddress, role "shipping"). A guest's typed
 * address survives the login redirect in localStorage.
 */

const DRAFT_KEY = 'bss.shop.shippingAddress';
const CFG = window.BSS_STOREFRONT_CONFIG || {};

/** The address form is the COUNTRY's, not the platform's. Each entry: how people
 * actually write an address there, which parts are required, and what the
 * regions are called. Unknown country = the generic European form. */
const COUNTRY_FORMS = {
  GY: {
    // "Lot 12 Camp Street, Georgetown, Region 4" — lots not house numbers, villages not
    // cities, and the 7-digit Guyana Post Office code (still lightly used, so a hint not a wall).
    fields: [
      { name: 'street1', label: 'Lot and street', placeholder: 'e.g. Lot 12 Camp Street' },
      { name: 'city', label: 'Village / town', placeholder: 'e.g. Georgetown, Diamond, Bartica' },
      { name: 'stateOrProvince', label: 'Region', options: [
        'Region 1 — Barima-Waini', 'Region 2 — Pomeroon-Supenaam', 'Region 3 — Essequibo Islands-West Demerara',
        'Region 4 — Demerara-Mahaica', 'Region 5 — Mahaica-Berbice', 'Region 6 — East Berbice-Corentyne',
        'Region 7 — Cuyuni-Mazaruni', 'Region 8 — Potaro-Siparuni', 'Region 9 — Upper Takutu-Upper Essequibo',
        'Region 10 — Upper Demerara-Berbice'] },
      { name: 'postCode', label: 'Postcode (7-digit GPOC code)', placeholder: 'e.g. 4131519 — find yours at guypost.gy' },
      { name: 'country', label: 'Country' },
    ],
  },
};
const GENERIC_FORM = {
  fields: [
    { name: 'street1', label: 'Street and number' },
    { name: 'postCode', label: 'Postal code' },
    { name: 'city', label: 'City' },
    { name: 'country', label: 'Country' },
  ],
};

export const ADDRESS_FIELDS = (COUNTRY_FORMS[CFG.country] || GENERIC_FORM).fields;
/** What must be filled before an address counts as complete (a region is a courtesy, not a gate). */
const REQUIRED = ['street1', 'postCode', 'city', 'country'];

export function isComplete(address) {
  return REQUIRED.every((f) => (address?.[f] || '').trim());
}

/** A fresh address starts in the operator's own country. */
export function defaultAddress() {
  return CFG.country ? { country: CFG.country } : {};
}

export function saveDraft(address) {
  localStorage.setItem(DRAFT_KEY, JSON.stringify(address));
}

export function loadDraft() {
  try {
    const saved = JSON.parse(localStorage.getItem(DRAFT_KEY));
    return saved && Object.keys(saved).length ? saved : defaultAddress();
  } catch {
    return defaultAddress();
  }
}

export function clearDraft() {
  localStorage.removeItem(DRAFT_KEY);
}

/** Is this medium the registry-verified address (freg F-P2 stamp)? The
 * shopper's own typed address is a plain postalAddress; the verified one
 * carries source=folkeregisteret and never replaces it. */
const isRegistered = (m) => m.mediumType === 'postalAddress'
  && m.characteristic?.source === 'folkeregisteret';

/** The postalAddress characteristic saved on the party, if any (the shopper's
 * own — the registry-verified medium is a separate stamp). */
export function addressOf(party) {
  const medium = (party?.contactMedium || [])
    .find((m) => m.mediumType === 'postalAddress' && !isRegistered(m));
  return medium?.characteristic || null;
}

/** contactMedium array with the shopper's postalAddress replaced by this
 * address; the registry stamp (and everything else) is preserved. */
export function withPostalAddress(party, address) {
  const others = (party?.contactMedium || [])
    .filter((m) => m.mediumType !== 'postalAddress' || isRegistered(m));
  return [...others, { mediumType: 'postalAddress', characteristic: address }];
}

/** The registry-verified address stamped on the party, if any. */
export function registeredAddressOf(party) {
  const medium = (party?.contactMedium || []).find(isRegistered);
  return medium?.characteristic || null;
}

/** contactMedium array with the registry stamp replaced by this characteristic
 * (which must carry source=folkeregisteret + verifiedAt). */
export function withRegisteredAddress(party, characteristic) {
  const others = (party?.contactMedium || []).filter((m) => !isRegistered(m));
  return [...others, { mediumType: 'postalAddress', characteristic }];
}

/** TMF622 place entry for order items that ship. */
export function shippingPlace(address) {
  return {
    role: 'shipping',
    '@type': 'GeographicAddress',
    ...address,
  };
}
