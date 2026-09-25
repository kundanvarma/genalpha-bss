/*
 * Shared plumbing for the split TMF client: the gateway paths, the fetch
 * helpers and the error shape. Every domain module imports from here; the
 * barrel (../api.js) re-exports the domains so callers never changed.
 */
import { authFetch } from '../auth.js';
export { authFetch, publicFetch, tokenClaims } from '../auth.js';
export const CATALOG = '/tmf-api/productCatalogManagement/v4';
export const ORDERING = '/tmf-api/productOrderingManagement/v4';
export const INVENTORY = '/tmf-api/productInventory/v4';
export const PARTY = '/tmf-api/party/v4';
export const STOCK = '/tmf-api/productStockManagement/v4';
export const BILLING = '/tmf-api/customerBillManagement/v4';
export const APPOINTMENT = '/tmf-api/appointment/v4';
export const TICKET = '/tmf-api/troubleTicket/v4';
export const PROBLEM = '/tmf-api/serviceProblemManagement/v4';
export const CART = '/tmf-api/shoppingCart/v4';
export const INTERACTION = '/tmf-api/partyInteraction/v4';
export async function json(res) {
  if (!res.ok) {
    const problem = await res.json().catch(() => ({}));
    throw new Error(problem.message || `HTTP ${res.status}`);
  }
  return res.json();
}
export const USAGE = '/tmf-api/usageConsumption/v4';
export const AGREEMENT = '/tmf-api/agreementManagement/v4';
export const SERVICE_INV = '/tmf-api/serviceInventory/v4';
export const PROMO = '/tmf-api/promotionManagement/v4';
export const VAULT = '/tmf-api/paymentMethods/v4';
export const RECOMMEND = '/tmf-api/recommendationManagement/v4';
export /* Number porting (MNP) — the customer's port-in/port-out orders, and the
 * agent-side actions: completing a scheduled cutover, and ceasing a service
 * (which releases its number — the port-out endgame). */
const PORTING = '/tmf-api/numberPortingManagement/v1';
export const KNOWLEDGE = '/tmf-api/knowledgeManagement/v4';
export /* ---------------- Device desk (device-commerce) ----------------
 * Staff faces of financing agreements, trade-in grading, the residual
 * table and withdrawal cases. Reads need device:read; the grading verdict,
 * revaluation calls and residual writes need device:write. */
const DEVICE = '/tmf-api/deviceCommerce/v1';
export /* ---------------- Migration desk (base-migration) ----------------
 * Reads are migration:read; every mutation is migration:admin. The arm
 * gate is server-enforced (409 without a simulation receipt) — the desk
 * mirrors it by disabling the button until state=simulated. */
const MIGRATION = '/tmf-api/baseMigration/v1';
export /* ---------------- Usage policies (spend meters, pools, auto top-up) ----
 * Staff query by partyId; usage:read may also PATCH the meters and the
 * auto top-up consent (SecurityConfig allows it), so the desk can raise
 * a roaming limit or lift a content bar with the customer on the line. */
const USAGE_POLICY = '/tmf-api/usageManagement/v4';
