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

/** The postalAddress characteristic saved on the party, if any. */
export function addressOf(party) {
  const medium = (party?.contactMedium || []).find((m) => m.mediumType === 'postalAddress');
  return medium?.characteristic || null;
}

/** contactMedium array with the postalAddress replaced by this address. */
export function withPostalAddress(party, address) {
  const others = (party?.contactMedium || []).filter((m) => m.mediumType !== 'postalAddress');
  return [...others, { mediumType: 'postalAddress', characteristic: address }];
}

/** TMF622 place entry for order items that ship. */
export function shippingPlace(address) {
  return {
    role: 'shipping',
    '@type': 'GeographicAddress',
    ...address,
  };
}
