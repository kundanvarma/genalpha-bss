/*
 * Thin client over the TMF663 shopping-cart service — the cart is
 * core-commerce state, not channel state. The browser keeps only the cart id
 * (a guest cart's bearer secret); signing in claims the cart for the party,
 * after which it follows the customer across devices and channels. Lines with
 * the same offering and configuration merge into one line's quantity.
 */
import { publicFetch } from './auth.js';

const CART_ID_KEY = 'bss.shop.cartId';
const CART = '/tmf-api/shoppingCart/v4/shoppingCart';
export const CART_EVENT = 'bss-cart-changed';

async function json(res) {
  if (!res.ok) {
    const problem = await res.json().catch(() => ({}));
    throw new Error(problem.message || `HTTP ${res.status}`);
  }
  return res.json();
}

/** The active server cart, creating one when none exists or ours went stale. */
async function fetchCart() {
  const id = localStorage.getItem(CART_ID_KEY);
  if (id) {
    const res = await publicFetch(`${CART}/${id}`);
    if (res.ok) {
      const cart = await res.json();
      if (cart.status === 'active') {
        return cart;
      }
    }
    localStorage.removeItem(CART_ID_KEY);
  }
  const cart = await json(await publicFetch(CART, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: '{}',
  }));
  localStorage.setItem(CART_ID_KEY, cart.id);
  return cart;
}

async function saveItems(cartId, lines) {
  await json(await publicFetch(`${CART}/${cartId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ cartItem: lines }),
  }));
  window.dispatchEvent(new Event(CART_EVENT));
}

function lineKey(offeringId, selections, characteristics) {
  const config = (selections || [])
    .map((s) => `${s.offeringId}:${JSON.stringify(Object.entries(s.characteristics || {}).sort())}`)
    .join('|');
  const own = JSON.stringify(Object.entries(characteristics || {}).sort());
  return `${offeringId}#${config}#${own}`;
}

export async function cartLines() {
  return (await fetchCart()).cartItem || [];
}

export async function cartCount() {
  return (await cartLines()).reduce((n, l) => n + (l.quantity || 0), 0);
}

/** selections: [{offeringId, name, characteristics}] for configured bundles;
 * characteristics: the line's OWN picks (a standalone device's colour);
 * deal: a label grouping lines added together as one advertised deal. */
export async function addToCart(offering, selections = [], quantity = 1, characteristics = null, deal = null) {
  const cart = await fetchCart();
  const lines = cart.cartItem || [];
  const key = lineKey(offering.id, selections, characteristics);
  const existing = lines.find((l) => l.key === key);
  if (existing) {
    existing.quantity += quantity;
    if (deal && !existing.deal) existing.deal = deal;
  } else {
    lines.push({ id: key, key, offeringId: offering.id, name: offering.name, quantity, selections,
      ...(characteristics && Object.keys(characteristics).length ? { characteristics } : {}),
      ...(deal ? { deal } : {}) });
  }
  await saveItems(cart.id, lines);
}

/** Deal adds are idempotent: two deals sharing the plan must not double it —
 * an existing line is only TAGGED, never incremented. */
export async function ensureInCart(offering, deal = null) {
  const cart = await fetchCart();
  const lines = cart.cartItem || [];
  const key = lineKey(offering.id, [], null);
  const existing = lines.find((l) => l.key === key);
  if (existing) {
    if (deal && !existing.deal) existing.deal = deal;
  } else {
    lines.push({ id: key, key, offeringId: offering.id, name: offering.name,
      quantity: 1, selections: [], ...(deal ? { deal } : {}) });
  }
  await saveItems(cart.id, lines);
}

/** Configure a line where it stands — the deal-added Samsung still needs
 * its colour. Conditioned prices reprice from the same picks. */
export async function setLineCharacteristics(key, characteristics) {
  const cart = await fetchCart();
  const lines = (cart.cartItem || []).map((l) => l.key === key
    ? { ...l, characteristics: { ...(l.characteristics || {}), ...characteristics } }
    : l);
  await saveItems(cart.id, lines);
}

export async function setQuantity(key, quantity) {
  const cart = await fetchCart();
  const lines = (cart.cartItem || []).map((l) => l.key === key ? { ...l, quantity } : l)
    .filter((l) => l.quantity > 0);
  await saveItems(cart.id, lines);
}

export async function removeLine(key) {
  const cart = await fetchCart();
  await saveItems(cart.id, (cart.cartItem || []).filter((l) => l.key !== key));
}

/** Signing in claims the guest cart for the party (no-op when already owned). */
export async function claimCart() {
  const id = localStorage.getItem(CART_ID_KEY);
  if (!id) return;
  await publicFetch(`${CART}/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: '{}',
  }).catch(() => {});
  window.dispatchEvent(new Event(CART_EVENT));
}

/** Checkout closes the cart into immutable history, linked to its order. */
export async function markCartCheckedOut(orderId) {
  const id = localStorage.getItem(CART_ID_KEY);
  if (id) {
    await publicFetch(`${CART}/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        status: 'checkedOut',
        relatedEntity: [{ id: orderId, '@referredType': 'ProductOrder' }],
      }),
    }).catch(() => {});
    localStorage.removeItem(CART_ID_KEY);
  }
  window.dispatchEvent(new Event(CART_EVENT));
}
