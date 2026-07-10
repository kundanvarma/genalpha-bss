/*
 * The cart lives in localStorage: it survives page loads and — importantly —
 * the Keycloak redirect when a guest signs in at checkout. Lines with the
 * same offering and configuration merge into one line's quantity.
 */

const CART_KEY = 'bss.shop.cart';
export const CART_EVENT = 'bss-cart-changed';

function load() {
  try {
    return JSON.parse(localStorage.getItem(CART_KEY)) || [];
  } catch {
    return [];
  }
}

function save(lines) {
  localStorage.setItem(CART_KEY, JSON.stringify(lines));
  window.dispatchEvent(new Event(CART_EVENT));
}

function lineKey(offeringId, selections) {
  const config = (selections || [])
    .map((s) => `${s.offeringId}:${JSON.stringify(Object.entries(s.characteristics || {}).sort())}`)
    .join('|');
  return `${offeringId}#${config}`;
}

export function cartLines() {
  return load();
}

export function cartCount() {
  return load().reduce((n, l) => n + l.quantity, 0);
}

/** selections: [{offeringId, name, characteristics}] for configured bundles. */
export function addToCart(offering, selections = [], quantity = 1) {
  const lines = load();
  const key = lineKey(offering.id, selections);
  const existing = lines.find((l) => l.key === key);
  if (existing) {
    existing.quantity += quantity;
  } else {
    lines.push({ key, offeringId: offering.id, name: offering.name, quantity, selections });
  }
  save(lines);
}

export function setQuantity(key, quantity) {
  const lines = load();
  const line = lines.find((l) => l.key === key);
  if (!line) return;
  line.quantity = quantity;
  save(quantity > 0 ? lines : lines.filter((l) => l.key !== key));
}

export function removeLine(key) {
  save(load().filter((l) => l.key !== key));
}

export function clearCart() {
  save([]);
}
