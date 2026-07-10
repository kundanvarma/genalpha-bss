/*
 * The one checkout path, used by the cart page and by the post-login resume:
 * mark physical lines via the stock service, require + persist the shipping
 * address when anything ships, and place the single TMF622 order.
 */
import { availabilityFor, checkoutCart, myParty, updateMyParty } from './api.js';
import { addressOf, isComplete, loadDraft, shippingPlace, withPostalAddress } from './address.js';

export async function performCheckout(lines) {
  const ids = [...new Set(lines.flatMap((l) => [l.offeringId, ...(l.selections || []).map((s) => s.offeringId)]))];
  const physical = Object.fromEntries(
    await Promise.all(ids.map(async (id) => [id, (await availabilityFor(id)) != null])));

  const annotated = lines.map((l) => ({
    ...l,
    physical: Boolean(physical[l.offeringId]),
    selections: (l.selections || []).map((s) => ({ ...s, physical: Boolean(physical[s.offeringId]) })),
  }));
  const needsShipping = annotated.some((l) => l.physical || l.selections.some((s) => s.physical));

  let address = loadDraft();
  if (needsShipping && !isComplete(address)) {
    const saved = addressOf(await myParty());
    if (saved && isComplete(saved)) {
      address = saved;
    } else {
      throw new Error('shipping address required');
    }
  }
  if (needsShipping) {
    const party = await myParty();
    await updateMyParty({ contactMedium: withPostalAddress(party, address) });
  }
  return checkoutCart(annotated, needsShipping ? shippingPlace(address) : null);
}
