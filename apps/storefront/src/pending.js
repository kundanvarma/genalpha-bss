/*
 * A guest's checkout intent — the cart itself survives the login redirect in
 * localStorage; this flag tells the app to finish the checkout right after
 * sign-in or registration completes.
 */
const PENDING_CHECKOUT_KEY = 'bss.shop.pendingCheckout';

export function setPendingCheckout() {
  localStorage.setItem(PENDING_CHECKOUT_KEY, '1');
}

export function takePendingCheckout() {
  const pending = localStorage.getItem(PENDING_CHECKOUT_KEY) === '1';
  localStorage.removeItem(PENDING_CHECKOUT_KEY);
  return pending;
}
