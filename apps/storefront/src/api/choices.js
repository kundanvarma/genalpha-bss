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
