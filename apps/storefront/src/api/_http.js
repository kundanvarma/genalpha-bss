/*
 * Shared plumbing for the split TMF client: the gateway paths, the fetch
 * helpers and the error shape. Every domain module imports from here; the
 * barrel (../api.js) re-exports the domains so callers never changed.
 */
import { authFetch, publicFetch, tokenClaims } from '../auth.js';
export { authFetch, publicFetch, tokenClaims } from '../auth.js';
export const CATALOG = '/tmf-api/productCatalogManagement/v4';
export const GEO = '/tmf-api/geographicAddressManagement/v4';
export const ORDERING = '/tmf-api/productOrderingManagement/v4';
export const INVENTORY = '/tmf-api/productInventory/v4';
export const PARTY = '/tmf-api/party/v4';
export const STOCK = '/tmf-api/productStockManagement/v4';
export const PAYMENT = '/tmf-api/paymentManagement/v4';
export async function json(res) {
  if (!res.ok) {
    const problem = await res.json().catch(() => ({}));
    const err = new Error(problem.message || `HTTP ${res.status}`);
    // TMF error bodies carry a machine code (e.g. CREDIT_FROZEN) — keep it so
    // the UI can branch on WHY, not just the words.
    err.code = problem.code || null;
    err.status = res.status;
    throw err;
  }
  return res.json();
}
export const COMM = '/tmf-api/communicationManagement/v4';
export // Track-my-delivery: the customer's own shipping orders (party-scoped by the
// fulfilment service). Each carries a carrier trackingRef once dispatched.
const SHIPPING = '/tmf-api/shippingOrderManagement/v4';
export // The order JOURNEY (TMF701): the milestones + a plain-language "why it's in
// progress", so a customer sees the whole story and never has to phone support.
const PROCESS = '/tmf-api/processFlowManagement/v4';
export const PAY = '/tmf-api/paymentManagement/v4';
export const CONSUMPTION = '/tmf-api/usageConsumption/v4';
export const AGREEMENT = '/tmf-api/agreementManagement/v4';
export const PROMOTION = '/tmf-api/promotionManagement/v4';
export const ADDRESS = '/tmf-api/geographicAddressManagement/v4';
export const RECOMMENDATION = '/tmf-api/recommendationManagement/v4';
export const PAYMENT_METHODS = '/tmf-api/paymentMethods/v4';
export /** Gift remaining GB — to a family member by id, or straight to a phone
 * number when the plan's giftScope reaches that far; usage verifies the
 * link (or resolves the number in the tenant's own pool) live. */
const LOYALTY = '/tmf-api/loyaltyManagement/v4';
export const CAMPAIGN = '/tmf-api/campaignManagement/v4';
export const BILLING = '/tmf-api/customerBillManagement/v4';
export const QUALIFICATION = '/tmf-api/productOfferingQualification/v4';
export const APPOINTMENT = '/tmf-api/appointment/v4';
export const TICKET = '/tmf-api/troubleTicket/v4';
export const COMMUNICATION = '/tmf-api/communicationManagement/v4';
export // Number porting (keep your number). Fail-soft: if the porting component
// is not deployed, checkout proceeds and the customer gets a new number.
const PORTING = '/tmf-api/numberPortingManagement/v1';
export // The running services from the orchestrator's inventory — carries the
// active number (drawn from the pool, or the one the customer ported in).
const SERVICE_INV = '/tmf-api/serviceInventory/v4';
export const INSIGHT = '/insight/v1';
export const VISITOR_KEY = 'bss.shop.visitor';
export const CONSENT_KEY = 'bss.shop.consent';
export const DEVICE = '/tmf-api/deviceCommerce/v1';
export const USAGE_MGMT = '/tmf-api/usageManagement/v4';
