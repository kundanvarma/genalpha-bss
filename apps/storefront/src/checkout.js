/*
 * The one checkout path, used by the cart page and by the post-login resume:
 * mark physical lines via the stock service, require + persist the shipping
 * address when anything ships, verify serviceability, authorize the one-time
 * charges when there are any, place the single TMF622 order, and book the
 * install appointment when the cart contains serviceability-gated offerings.
 */
import { availabilityFor, checkQualification, checkoutCart, createAppointment, createPayment,
  getOffering, myParty, priceIndex, updateMyParty, validateAddress, requestPortIn } from './api.js';
import { addressOf, isComplete, loadDraft, shippingPlace, withPostalAddress } from './address.js';
import { oneTimeTotal, pricesOf } from './money.js';

/** Card details never persist anywhere — reaching here without them stops the flow. */
export const PAYMENT_REQUIRED = 'PAYMENT_REQUIRED';

/** A chosen install slot survives the login redirect like the address does. */
const SLOT_KEY = 'bss.shop.installSlot';

export function saveSlotDraft(slot) {
  if (slot) {
    localStorage.setItem(SLOT_KEY, JSON.stringify(slot));
  } else {
    localStorage.removeItem(SLOT_KEY);
  }
}

export function loadSlotDraft() {
  try {
    return JSON.parse(localStorage.getItem(SLOT_KEY));
  } catch {
    return null;
  }
}

/**
 * Everything orderable in these cart lines, bundle children included —
 * the unit for serviceability checks.
 */
export function qualificationItems(lines, offerings) {
  const seen = new Map();
  for (const line of lines) {
    const bundle = offerings[line.offeringId];
    seen.set(line.offeringId, line.name);
    for (const child of (bundle?.bundledProductOffering || [])) {
      if (child.id) seen.set(child.id, child.name);
    }
    for (const s of (line.selections || [])) {
      seen.set(s.offeringId, s.name);
    }
  }
  return [...seen.entries()].map(([offeringId, name]) => ({ offeringId, name }));
}

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

export async function performCheckout(lines, card = null, promotionCode = null, keepNumber = null) {
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

  // A first qualification probe tells us whether anything is
  // serviceability-gated — gated offerings need an address even when nothing
  // physically ships (a fiber install has a "where").
  const qItems = qualificationItems(lines, offerings);
  let address = loadDraft();
  let check = await checkQualification(qItems,
      { postCode: address.postCode, city: address.city, country: address.country });
  let items = check.productOfferingQualificationItem || [];
  const needsInstall = items.some((i) => i.serviceabilityGated);

  if ((needsShipping || needsInstall) && !isComplete(address)) {
    const saved = addressOf(await myParty());
    if (saved && isComplete(saved)) {
      address = saved;
      check = await checkQualification(qItems,
          { postCode: address.postCode, city: address.city, country: address.country });
      items = check.productOfferingQualificationItem || [];
    } else {
      throw new Error('shipping address required');
    }
  }

  // TMF673: the address must be real before anything ships or installs.
  // The standardized form (trimmed, cased, postcode compacted) is what the
  // order, qualification and appointment all see.
  if (needsShipping || needsInstall) {
    const verdict = await validateAddress(address);
    if (verdict.validationResult !== 'success') {
      throw new Error(verdict.validationReason || 'address could not be validated');
    }
    address = { ...address, ...verdict.standardizedGeographicAddress };
  }
  if (needsShipping || needsInstall) {
    const party = await myParty();
    await updateMyParty({ contactMedium: withPostalAddress(party, address) });
  }

  // One unqualified gated offering sinks the checkout with its reason.
  const unqualified = items.find((i) => i.qualificationItemResult === 'unqualified');
  if (unqualified) {
    throw new Error(unqualified.eligibilityUnavailabilityReason?.[0]?.label || 'not serviceable at this address');
  }
  const slot = loadSlotDraft();
  if (needsInstall && !slot) {
    throw new Error('installation time slot required');
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

  // Keep-your-number: port the customer's existing number in before the order
  // is placed, so provisioning activates on it instead of drawing from the pool.
  if (keepNumber && keepNumber.number) {
    const me = await myParty();
    await requestPortIn(me.id, keepNumber.number, keepNumber.currentProvider).catch(() => {});
  }
  const order = await checkoutCart(annotated, needsShipping ? shippingPlace(address) : null, paymentRefs,
    promotionCode);
  if (needsInstall) {
    await createAppointment(slot, order.id, shippingPlace(address),
        'Installation: ' + lines.map((l) => l.name).join(', '));
    saveSlotDraft(null);
  }
  return order;
}
