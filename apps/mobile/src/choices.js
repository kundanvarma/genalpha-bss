/*
 * Benefit Choices from the app (#389): the household's pool, the benefits on
 * offer, a pick and a release. Every change is an ordinary product order, the
 * same the shop places; the ledger counts and ordering decides — the app asks.
 */
import { getToken } from './auth.js';
import { API_BASE } from './config.js';

const ORDERS = '/tmf-api/productOrderingManagement/v4/productOrder';

async function call(path, options = {}) {
  const res = await fetch(API_BASE + path, {
    ...options,
    headers: { 'X-Channel': 'app', ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      Authorization: 'Bearer ' + getToken() },
  });
  if (!res.ok) {
    // an unreadable error body still says what happened: the status
    const problem = await res.json().catch(() => ({ message: `the BSS answered ${res.status}` }));
    throw new Error(problem.message || `HTTP ${res.status}`);
  }
  return res.json();
}

/** My household's pool, under the operator's own word for a Choice. The party is my token. */
export const myChoices = () => call('/benefitAllowance/v1/pool');

/** Every benefit on sale, page by page — the catalog caps a page at 100. */
export async function choiceBenefits() {
  const found = [];
  for (let offset = 0; ; offset += 100) {
    const page = await call(`/tmf-api/productCatalogManagement/v4/productOffering?limit=100&offset=${offset}&lifecycleStatus=Active`);
    found.push(...page.filter((o) => o.choiceCost > 0));
    if (page.length < 100) return found;
  }
}

/** The product a benefit lives in: the one its picking order made (TMF637 lineage). */
export async function productOfPick(allocation) {
  const products = await call('/tmf-api/productInventory/v4/product?status=active&limit=100');
  return products.find((p) => (p.productOrderItem || []).some((l) => l.productOrderId === allocation.orderId)) || null;
}

/** Pick a benefit: an order; ordering holds its Choices (refused with the numbers when too few are left). */
export const pickBenefit = (offering) => call(ORDERS, { method: 'POST', body: JSON.stringify({
  description: offering.name,
  productOrderItem: [{ id: '1', action: 'add', quantity: 1,
    productOffering: { id: offering.id, name: offering.name, '@referredType': 'ProductOffering' } }],
}) });

/** Give a benefit back: ending its product frees the Choice. */
export const releaseBenefit = (product) => call(ORDERS, { method: 'POST', body: JSON.stringify({
  productOrderItem: [{ id: '1', action: 'delete', product: { id: product.id } }],
}) });
