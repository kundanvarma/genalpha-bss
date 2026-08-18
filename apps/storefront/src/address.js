/*
 * Shipping address helpers. The saved address lives on the TMF632 individual
 * as a postalAddress contactMedium; on orders it rides each physical item as
 * a TMF622 place (GeographicAddress, role "shipping"). A guest's typed
 * address survives the login redirect in localStorage.
 */

const DRAFT_KEY = 'bss.shop.shippingAddress';

export const ADDRESS_FIELDS = [
  { name: 'street1', label: 'Street and number' },
  { name: 'postCode', label: 'Postal code' },
  { name: 'city', label: 'City' },
  { name: 'country', label: 'Country' },
];

export function isComplete(address) {
  return ADDRESS_FIELDS.every((f) => (address?.[f.name] || '').trim());
}

export function saveDraft(address) {
  localStorage.setItem(DRAFT_KEY, JSON.stringify(address));
}

export function loadDraft() {
  try {
    return JSON.parse(localStorage.getItem(DRAFT_KEY)) || {};
  } catch {
    return {};
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
