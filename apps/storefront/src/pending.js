/**
 * A guest's configured order — survives the login redirect at checkout.
 * Shape: { offeringId, selections: [{offeringId, characteristics}] }
 */
export const PENDING_ORDER_KEY = 'bss.shop.pendingOrder';

export function stashPendingOrder(offering, configuredItems) {
  sessionStorage.setItem(PENDING_ORDER_KEY, JSON.stringify({
    offeringId: offering.id,
    selections: configuredItems.map((c) => ({
      offeringId: c.offering.id,
      characteristics: c.characteristics || {},
    })),
  }));
}

export function takePendingOrder() {
  const raw = sessionStorage.getItem(PENDING_ORDER_KEY);
  if (!raw) return null;
  sessionStorage.removeItem(PENDING_ORDER_KEY);
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}
