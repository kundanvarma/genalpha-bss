/*
 * The one checkout path, used by the cart page and by the post-login resume:
 * mark physical lines via the stock service, require + persist the shipping
 * address when anything ships, authorize the one-time charges when there are
 * any, and place the single TMF622 order.
 */
import { availabilityFor, checkoutCart, createPayment, getOffering, myParty, priceIndex, updateMyParty } from './api.js';
import { addressOf, isComplete, loadDraft, shippingPlace, withPostalAddress } from './address.js';
import { oneTimeTotal, pricesOf } from './money.js';

/** Card details never persist anywhere — reaching here without them stops the flow. */
export const PAYMENT_REQUIRED = 'PAYMENT_REQUIRED';

/** What the cart owes at checkout: one-time charges across all lines. */
export function dueNow(lines, offerings, prices) {
  let total = null;
  for (const line of lines) {
    for (const id of [line.offeringId, ...(line.selections || []).map((s) => s.offeringId)]) {
      const offering = offerings[id];
      if (!offering) continue;
      const once = oneTimeTotal(pricesOf(offering, prices));
      if (once) {
        total = total
          ? { value: total.value + once.value * line.quantity, unit: total.unit }
          : { value: once.value * line.quantity, unit: once.unit };
      }
    }
  }
  return total;
}

export async function performCheckout(lines, card = null) {
  const ids = [...new Set(lines.flatMap((l) => [l.offeringId, ...(l.selections || []).map((s) => s.offeringId)]))];
  const [physicalEntries, offeringList, prices] = await Promise.all([
    Promise.all(ids.map(async (id) => [id, (await availabilityFor(id)) != null])),
    Promise.all(ids.map(getOffering)),
    priceIndex(),
  ]);
  const physical = Object.fromEntries(physicalEntries);
  const offerings = Object.fromEntries(offeringList.map((o) => [o.id, o]));

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

  let paymentRefs = null;
  const due = dueNow(lines, offerings, prices);
  if (due) {
    if (!card) {
      throw new Error(PAYMENT_REQUIRED);
    }
    const payment = await createPayment(due, card,
        'One-time charges: ' + lines.map((l) => l.name).join(', '));
    paymentRefs = [{ id: payment.id, href: payment.href, '@referredType': 'Payment' }];
  }

  return checkoutCart(annotated, needsShipping ? shippingPlace(address) : null, paymentRefs);
}
