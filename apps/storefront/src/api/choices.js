// choices: the household's Choices (#354) — what the plans give, what is in use, what is left.
import { ORDERING, authFetch, json } from './_http.js';

const CHOICES = '/benefitAllowance/v1';

/** My household's pool, under this tenant's own word for a Choice. The party is my token. */
export async function myChoices() {
  return json(await authFetch(`${CHOICES}/pool`));
}

/** Pick a benefit: an ordinary TMF622 order; ordering holds its Choices (409 when too few are left). */
export async function pickBenefit(offering) {
  return json(await authFetch(`${ORDERING}/productOrder`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      description: offering.name,
      productOrderItem: [{
        id: '1', action: 'add', quantity: 1,
        productOffering: { id: offering.id, name: offering.name, '@referredType': 'ProductOffering' },
      }],
    }),
  }));
}

/** Give a benefit back: a TMF622 order that ends its product; the Choice returns once it completes. */
export async function releaseBenefit(productId) {
  return json(await authFetch(`${ORDERING}/productOrder`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ productOrderItem: [{ id: '1', action: 'delete', product: { id: productId } }] }),
  }));
}

/** Swap: ONE order that ends the old benefit and picks the new — the old stays until the new is active. */
export async function swapBenefit(productId, offering) {
  return json(await authFetch(`${ORDERING}/productOrder`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      description: `Swap for ${offering.name}`,
      productOrderItem: [
        { id: '1', action: 'delete', product: { id: productId } },
        { id: '2', action: 'add', quantity: 1,
          productOffering: { id: offering.id, name: offering.name, '@referredType': 'ProductOffering' } },
      ],
    }),
  }));
}

/** "Can I pick this?" — ordering's answer before an order, from the order's own checks; holds nothing (#392). */
export async function choicePreview(offeringId) {
  return json(await authFetch(`${ORDERING}/choicePreview?offeringId=${encodeURIComponent(offeringId)}`));
}
