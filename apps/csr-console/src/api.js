/*
 * Agent-side TMF client, same-origin through the gateway. Agents are
 * org-scoped on tickets and interactions by the backends; customer lookups
 * use the relatedPartyId filters the services expose to staff roles.
 */
import { authFetch } from './auth.js';

const CATALOG = '/tmf-api/productCatalogManagement/v4';
const ORDERING = '/tmf-api/productOrderingManagement/v4';
const INVENTORY = '/tmf-api/productInventory/v4';
const PARTY = '/tmf-api/party/v4';
const STOCK = '/tmf-api/productStockManagement/v4';
const BILLING = '/tmf-api/customerBillManagement/v4';
const APPOINTMENT = '/tmf-api/appointment/v4';
const TICKET = '/tmf-api/troubleTicket/v4';
const PROBLEM = '/tmf-api/serviceProblemManagement/v4';

/** TMF656 open outages — fail-soft when assurance is not deployed. */
export async function openProblems() {
  try {
    return await json(await authFetch(`${PROBLEM}/serviceProblem?status=open`));
  } catch {
    return [];
  }
}
const CART = '/tmf-api/shoppingCart/v4';
const INTERACTION = '/tmf-api/partyInteraction/v4';

async function json(res) {
  if (!res.ok) {
    const problem = await res.json().catch(() => ({}));
    throw new Error(problem.message || `HTTP ${res.status}`);
  }
  return res.json();
}

export async function searchCustomers(q) {
  const filter = q ? `&q=${encodeURIComponent(q)}` : '';
  return json(await authFetch(`${PARTY}/individual?limit=50${filter}`));
}

/** A typed MSISDN resolves in the tenant's own number pool (agents are
 * unscoped there); empty when nobody holds it. */
export async function customerByNumber(number) {
  const res = await authFetch(`/tmf-api/serviceInventory/v4/numberOwner?number=${encodeURIComponent(number)}`);
  if (!res.ok) return null;
  const owner = await res.json();
  const customer = await authFetch(`${PARTY}/individual/${owner.partyId}`);
  return customer.ok ? customer.json() : null;
}

export async function getCustomer(id) {
  return json(await authFetch(`${PARTY}/individual/${id}`));
}

/** Re-verify a customer's address against the national registry (freg F-P4).
 * Back-office callers may vouch for the customer by id — the match event is
 * attributed to THEM, so the CDP re-homes movers. Returns the registryMatch
 * part, or null when the address fails validation outright. */
export async function verifyPartyAddress(customer, address) {
  const name = [customer.givenName, customer.familyName].filter(Boolean).join(' ');
  const result = await json(await authFetch('/tmf-api/geographicAddressManagement/v4/geographicAddressValidation', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ submittedGeographicAddress: address, relatedParty: { id: customer.id, name } }),
  }));
  return result.registryMatch || null;
}

export async function ordersOf(customerId) {
  return json(await authFetch(`${ORDERING}/productOrder?limit=100&relatedPartyId=${customerId}`));
}

export async function patchOrder(id, patch) {
  return json(await authFetch(`${ORDERING}/productOrder/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  }));
}

export async function productsOf(customerId) {
  return json(await authFetch(`${INVENTORY}/product?limit=100&relatedPartyId=${customerId}`));
}

export async function billsOf(customerId) {
  return json(await authFetch(`${BILLING}/customerBill?limit=100&relatedPartyId=${customerId}`));
}

export async function appointmentsOf(customerId) {
  return json(await authFetch(`${APPOINTMENT}/appointment?limit=100&relatedPartyId=${customerId}`));
}

export async function ticketsOf(customerId) {
  return json(await authFetch(`${TICKET}/troubleTicket?limit=100&relatedPartyId=${customerId}`));
}

export async function orgTickets(status) {
  const filter = status ? `&status=${status}` : '';
  return json(await authFetch(`${TICKET}/troubleTicket?limit=100${filter}`));
}

export async function createTicket(body) {
  return json(await authFetch(`${TICKET}/troubleTicket`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }));
}

export async function workTicket(id, patch) {
  return json(await authFetch(`${TICKET}/troubleTicket/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  }));
}

/** One PAGE of the timeline (newest first) + the total, so the 360 shows
 * a handful and fetches more on demand instead of hauling the history. */
export async function interactionsPage(customerId, offset = 0, limit = 5) {
  const res = await authFetch(
    `${INTERACTION}/partyInteraction?offset=${offset}&limit=${limit}&relatedPartyId=${customerId}`);
  if (!res.ok) throw new Error(`interactions: ${res.status}`);
  return {
    items: await res.json(),
    total: Number(res.headers.get('X-Total-Count') || 0),
  };
}

export async function logInteraction(body) {
  return json(await authFetch(`${INTERACTION}/partyInteraction`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }));
}

export async function cartsOf(customerId) {
  return json(await authFetch(`${CART}/shoppingCart?limit=10&status=active&relatedPartyId=${customerId}`));
}

const USAGE = '/tmf-api/usageConsumption/v4';
const AGREEMENT = '/tmf-api/agreementManagement/v4';
const SERVICE_INV = '/tmf-api/serviceInventory/v4';
const PROMO = '/tmf-api/promotionManagement/v4';
const VAULT = '/tmf-api/paymentMethods/v4';
const RECOMMEND = '/tmf-api/recommendationManagement/v4';

/* CSR 360 catch-up reads — all fail-soft: a deployment without the
 * component simply shows an empty card. */
export async function usageOf(customerId) {
  try {
    const report = await json(await authFetch(`${USAGE}/queryUsageConsumption?relatedPartyId=${customerId}`));
    return report.bucket || [];
  } catch { return []; }
}

export async function agreementsOf(customerId) {
  try {
    return await json(await authFetch(`${AGREEMENT}/agreement?relatedPartyId=${customerId}&limit=50`));
  } catch { return []; }
}

export async function activeServicesOf(customerId) {
  try {
    return await json(await authFetch(`${SERVICE_INV}/service?relatedPartyId=${customerId}`));
  } catch { return []; }
}

/* Number porting (MNP) — the customer's port-in/port-out orders, and the
 * agent-side actions: completing a scheduled cutover, and ceasing a service
 * (which releases its number — the port-out endgame). */
const PORTING = '/tmf-api/numberPortingManagement/v1';

export async function portingOrdersOf(customerId) {
  try {
    return await json(await authFetch(`${PORTING}/numberPortingOrder?relatedPartyId=${customerId}`));
  } catch { return []; }
}

export async function completeCutover(portingOrderId) {
  return json(await authFetch(`${PORTING}/numberPortingOrder/${portingOrderId}/complete`, {
    method: 'POST',
  }));
}

export async function ceaseService(serviceId, reason) {
  return json(await authFetch(`${SERVICE_INV}/service/${serviceId}/terminate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason: reason || 'ceased by agent' }),
  }));
}

export async function redemptionsOf(customerId) {
  try {
    return await json(await authFetch(`${PROMO}/redemption?relatedPartyId=${customerId}`));
  } catch { return []; }
}

export async function paymentMethodsOf(customerId) {
  try {
    return await json(await authFetch(`${VAULT}/paymentMethod?relatedPartyId=${customerId}`));
  } catch { return []; }
}

export async function revokePaymentMethod(id) {
  const res = await authFetch(`${VAULT}/paymentMethod/${id}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
}

export async function recommendationsOf(customerId) {
  try {
    const recs = await json(await authFetch(`${RECOMMEND}/recommendation?relatedPartyId=${customerId}`));
    return recs[0]?.recommendationItem || [];
  } catch { return []; }
}

export async function stockLevels() {
  return json(await authFetch(`${STOCK}/productStock?limit=100`));
}

export async function offeringNames() {
  const offerings = await json(await authFetch(`${CATALOG}/productOffering?limit=100`));
  return Object.fromEntries(offerings.map((o) => [o.id, o.name]));
}

// Intelligence copilot — fail-soft like every optional component: if the
// module is not deployed, the copilot card simply does not render results.
export async function aiCustomerSummary(payload) {
  const res = await authFetch('/ai/v1/customerSummary', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || `HTTP ${res.status}`);
  return res.json();
}

export async function aiTicketReply(payload) {
  const res = await authFetch('/ai/v1/ticketReply', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || `HTTP ${res.status}`);
  return res.json();
}

const KNOWLEDGE = '/tmf-api/knowledgeManagement/v4';

/** The library, audience-filtered by the agent's own token. */
export async function searchKnowledge(q) {
  const res = await authFetch(`${KNOWLEDGE}/article${q ? `?q=${encodeURIComponent(q)}` : ''}`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

/** Grounded answer with sources — retrieval runs as the asker. */
/** The shelf for one screen: articles tagged for it, audience-gated by the server. */
export async function shelfKnowledge(tag) {
  const res = await authFetch(`${KNOWLEDGE}/article?tag=${encodeURIComponent(tag)}`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

export async function askKnowledge(question, context) {
  const res = await authFetch('/ai/v1/knowledgeAsk', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question, context }),
  });
  if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || `HTTP ${res.status}`);
  return res.json();
}

/** Next best offer with the WHY — TMF680 candidates, the model reasons. */
/** The SIM behind a service — the PUK only with reveal (read it to the
 * caller AFTER verifying identity; the disclosure is logged). */
export async function simOf(serviceId, reveal = false) {
  const res = await authFetch(`/tmf-api/serviceInventory/v4/service/${serviceId}/sim${reveal ? '?reveal=true' : ''}`);
  if (!res.ok) return null;
  return res.json();
}

/** OTA PIN reset through the SIM-platform seam; the owner is notified. */
export async function resetSimPin(serviceId, newPin) {
  return json(await authFetch(`/tmf-api/serviceInventory/v4/service/${serviceId}/sim/resetPin`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ newPin }),
  }));
}

/** Lost/stolen/damaged/upgrade: block the old card at the network and
 * mint a fresh one against the same service — the number never moves. */
export async function replaceSim(serviceId, reason) {
  return json(await authFetch(`/tmf-api/serviceInventory/v4/service/${serviceId}/sim/replace`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  }));
}

/** MSISDN change: old number quarantined, new one drawn onto the SAME
 * service — SIM, usage and billing untouched; the customer is warned. */
export async function changeNumber(serviceId) {
  return json(await authFetch(`/tmf-api/serviceInventory/v4/service/${serviceId}/changeNumber`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }));
}

/** Vacation hold: pause the line (charging pauses, number and SIM stay);
 * the hold lifts itself at the agreed date, or on request. */
export async function suspendService(serviceId, days) {
  return json(await authFetch(`/tmf-api/serviceInventory/v4/service/${serviceId}/suspend`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(days ? { days } : {}),
  }));
}

export async function resumeService(serviceId) {
  return json(await authFetch(`/tmf-api/serviceInventory/v4/service/${serviceId}/resume`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }));
}

/** Triage before ticket: outage on their path? out of data? paused? */
export async function diagnoseService(serviceId) {
  return json(await authFetch(`/tmf-api/serviceInventory/v4/service/${serviceId}/diagnose`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }));
}

/** TRANSFER a line to another person — number, SIM and usage move with
 * it; the payer stamp stays (a company-paid line keeps its payer). */
export async function transferService(serviceId, toPartyId) {
  return json(await authFetch(`/tmf-api/serviceInventory/v4/service/${serviceId}/transfer`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ toPartyId }),
  }));
}

export async function findCustomerByEmail(q) {
  return json(await authFetch(`/tmf-api/party/v4/individual?q=${encodeURIComponent(q)}&limit=5`));
}

/** "This charge is wrong": open a dispute for the caller. */
/** The reversing document — staff-only (billing:admin); reason REQUIRED. */
export async function issueCreditNote(billId, amount, reason) {
  return json(await authFetch(`${BILLING}/customerBill/${billId}/creditNote`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...(amount ? { amount } : {}), reason }),
  }));
}

export async function creditNotesOf(billId) {
  const res = await authFetch(`${BILLING}/creditNote?billId=${billId}`);
  return res.ok ? res.json() : [];
}

export async function disputeBill(billId, reason) {
  return json(await authFetch(`/tmf-api/customerBillManagement/v4/customerBill/${billId}/dispute`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  }));
}

/** The bill as the customer sees it — a PDF, opened in a new tab. */
export async function openBillPdf(billId) {
  const res = await authFetch(`${BILLING}/customerBill/${billId}/document.pdf`);
  if (!res.ok) throw new Error('could not fetch the bill PDF');
  window.open(URL.createObjectURL(await res.blob()), '_blank');
}

/** "Send me a copy of my invoice" — emails the PDF to the address on
 * file (never one dictated over the phone). */
export async function resendBill(billId) {
  return json(await authFetch(`${BILLING}/customerBill/${billId}/resend`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
  }));
}

/** How the customer's bill arrives (paper / einvoice / digital) — set
 * on their behalf, with their say-so on the line. */
export async function setBillDeliveryFor(customerId, preference) {
  return json(await authFetch(`${PARTY}/individual/${customerId}/billDelivery`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ preference }),
  }));
}

/* ---------------- Collections desk (billing) ----------------
 * Cases are made by the sweeper, never POSTed. Reads ride billing:read;
 * holds, release, write-off, the sweep trigger and the policy editor are
 * billing:admin; the promise-to-pay entry is billing:write (staff carry it). */
export async function collectionCases(state) {
  return json(await authFetch(`${BILLING}/collectionCase${state ? `?state=${encodeURIComponent(state)}` : ''}`));
}

export async function collectionCaseById(id) {
  return json(await authFetch(`${BILLING}/collectionCase/${id}`));
}

/** {days?, amount?} — amount defaults to the full overdue balance. */
export async function casePromiseToPay(id, body) {
  return json(await authFetch(`${BILLING}/collectionCase/${id}/promiseToPay`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {}),
  }));
}

/** {type: 'dispute', amount} freezes ONLY the disputed amount;
 *  {type: 'hardship'} is the manual full hold. */
export async function caseHold(id, body) {
  return json(await authFetch(`${BILLING}/collectionCase/${id}/hold`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }));
}

export async function caseRelease(id, type) {
  return json(await authFetch(`${BILLING}/collectionCase/${id}/release`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type }),
  }));
}

/** The ladder's end, human decision: reason REQUIRED (the auditors will ask). */
export async function caseWriteOff(id, reason) {
  return json(await authFetch(`${BILLING}/collectionCase/${id}/writeOff`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  }));
}

/** Walk this tenant's ladder now instead of waiting for the scheduled tick. */
export async function runCollectionSweep() {
  return json(await authFetch(`${BILLING}/collectionSweep`, { method: 'POST' }));
}

export async function dunningPolicies() {
  return json(await authFetch(`${BILLING}/dunningPolicy`));
}

export async function patchDunningPolicy(id, patch) {
  return json(await authFetch(`${BILLING}/dunningPolicy/${id}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  }));
}

/** Hardship/retention: split an unpaid bill into monthly installments. */
export async function splitBill(billId, installments) {
  return json(await authFetch(`/tmf-api/customerBillManagement/v4/customerBill/${billId}/installmentPlan`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ installments }),
  }));
}

/** UPSELL, acted on: order the suggested offering ON BEHALF of the
 * customer (with their say-so on the line) — the agent is unscoped, so
 * the relatedParty in the body names the owner. */
export async function orderForCustomer(customerId, offering) {
  return json(await authFetch(`${ORDERING}/productOrder`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      productOrderItem: [{ action: 'add',
        productOffering: { id: offering.id, name: offering.name } }],
      relatedParty: [{ id: customerId, role: 'customer', '@referredType': 'Individual' }],
    }),
  }));
}

/** Or send the offer instead: a personal message that lands in the inbox
 * (and the ESP, and the interaction timeline — the whole omnichannel loop). */
export async function sendOffer(customerId, offering, agentName) {
  return json(await authFetch('/tmf-api/communicationManagement/v4/communicationMessage', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      subject: `An offer picked for you: ${offering.name}`,
      content: `${agentName || 'Your agent'} thought ${offering.name} fits how you use your services. `
        + 'Find it in the shop, or reply to this message and we will set it up.',
      relatedParty: [{ id: customerId, role: 'customer', '@referredType': 'Individual' }],
    }),
  }));
}

/* ---------------- Device desk (device-commerce) ----------------
 * Staff faces of financing agreements, trade-in grading, the residual
 * table and withdrawal cases. Reads need device:read; the grading verdict,
 * revaluation calls and residual writes need device:write. */
const DEVICE = '/tmf-api/deviceCommerce/v1';

export async function deviceAgreements(status) {
  return json(await authFetch(`${DEVICE}/deviceAgreement${status ? `?status=${encodeURIComponent(status)}` : ''}`));
}

export async function tradeInValuations(status) {
  return json(await authFetch(`${DEVICE}/tradeInValuation${status ? `?status=${encodeURIComponent(status)}` : ''}`));
}

/** The grading verdict: {finalGrade, finalValue, note?} — zero delta settles,
 * anything else moves to `revalued` and waits on the customer. */
export async function gradeTradeIn(id, body) {
  return json(await authFetch(`${DEVICE}/tradeInValuation/${id}/grading`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }));
}

export async function acceptRevaluation(id) {
  return json(await authFetch(`${DEVICE}/tradeInValuation/${id}/acceptRevaluation`, { method: 'POST' }));
}

export async function rejectRevaluation(id) {
  return json(await authFetch(`${DEVICE}/tradeInValuation/${id}/rejectRevaluation`, { method: 'POST' }));
}

export async function residualTable(deviceRef) {
  return json(await authFetch(`${DEVICE}/tradeInResidual${deviceRef ? `?deviceRef=${encodeURIComponent(deviceRef)}` : ''}`));
}

/** Upsert by (deviceRef, ageMonths): {deviceRef, ageMonths, baseValue, currency?}. */
export async function upsertResidual(body) {
  return json(await authFetch(`${DEVICE}/tradeInResidual`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }));
}

export async function deleteResidual(id) {
  const res = await authFetch(`${DEVICE}/tradeInResidual/${id}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
}

export async function withdrawalCases() {
  return json(await authFetch(`${DEVICE}/withdrawalCase`));
}

/* ---------------- Migration desk (base-migration) ----------------
 * Reads are migration:read; every mutation is migration:admin. The arm
 * gate is server-enforced (409 without a simulation receipt) — the desk
 * mirrors it by disabling the button until state=simulated. */
const MIGRATION = '/tmf-api/baseMigration/v1';

export async function migrationPlans() {
  return json(await authFetch(`${MIGRATION}/migrationPlan?limit=100`));
}

export async function migrationPlan(id) {
  return json(await authFetch(`${MIGRATION}/migrationPlan/${id}`));
}

/** {name, matrix: [{sourceOfferingId, targetOfferingId, deltaClass}],
 *  eligibility: {inBinding}, trigger: {type}, jurisdictionPack: {noticeDays}} */
export async function createMigrationPlan(body) {
  return json(await authFetch(`${MIGRATION}/migrationPlan`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }));
}

export async function attachMigrationSimulation(id, simulationRef) {
  return json(await authFetch(`${MIGRATION}/migrationPlan/${id}/attachSimulation`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ simulationRef }),
  }));
}

export async function armMigrationPlan(id) {
  return json(await authFetch(`${MIGRATION}/migrationPlan/${id}/arm`, { method: 'POST' }));
}

export async function pauseMigrationPlan(id) {
  return json(await authFetch(`${MIGRATION}/migrationPlan/${id}/pause`, { method: 'POST' }));
}

export async function resumeMigrationPlan(id) {
  return json(await authFetch(`${MIGRATION}/migrationPlan/${id}/resume`, { method: 'POST' }));
}

export async function migrationProgress(id) {
  return json(await authFetch(`${MIGRATION}/migrationPlan/${id}/progress`));
}

export async function migrationCustomers(id, state) {
  return json(await authFetch(`${MIGRATION}/migrationPlan/${id}/customer?limit=200${state ? `&state=${encodeURIComponent(state)}` : ''}`));
}

export async function exitMigrationCustomer(id, cid) {
  return json(await authFetch(`${MIGRATION}/migrationPlan/${id}/customer/${cid}/exit`, { method: 'POST' }));
}

export async function rollbackMigrationCustomer(id, cid) {
  return json(await authFetch(`${MIGRATION}/migrationPlan/${id}/customer/${cid}/rollback`, { method: 'POST' }));
}

/* ---------------- Usage policies (spend meters, pools, auto top-up) ----
 * Staff query by partyId; usage:read may also PATCH the meters and the
 * auto top-up consent (SecurityConfig allows it), so the desk can raise
 * a roaming limit or lift a content bar with the customer on the line. */
const USAGE_POLICY = '/tmf-api/usageManagement/v4';

export async function spendPoliciesOf(partyId) {
  try {
    return await json(await authFetch(`${USAGE_POLICY}/spendPolicy?partyId=${encodeURIComponent(partyId)}`));
  } catch { return []; }
}

export async function patchSpendPolicy(partyId, meterType, body) {
  return json(await authFetch(`${USAGE_POLICY}/spendPolicy/${meterType}?partyId=${encodeURIComponent(partyId)}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }));
}

/** Pools the party owns or draws from — staff list is tenant-wide, so
 * filter to this customer here. */
export async function poolsOf(partyId) {
  try {
    const all = await json(await authFetch(`${USAGE_POLICY}/allowancePool`));
    return all.filter((p) => p.ownerPartyId === partyId
      || (p.member || []).some((m) => m.partyId === partyId));
  } catch { return []; }
}

export async function autoTopupOf(partyId) {
  try {
    return await json(await authFetch(`${USAGE_POLICY}/autoTopupPolicy?partyId=${encodeURIComponent(partyId)}`));
  } catch { return null; }
}

/* ---------------- Credit decisions (ordering credit seam) ----------------
 * The stored decisions only — there is never a report to show. */
export async function creditDecisionsOf(partyId) {
  try {
    return await json(await authFetch(`${ORDERING}/creditDecision?relatedPartyId=${encodeURIComponent(partyId)}`));
  } catch { return []; }
}

/* ---------------- Registry & directory tools (party-account) ----------------
 * Back-office: link a party to its registry person, poke the sync worker,
 * curate directory exposure, and run/inspect the directory delta export. */
export async function linkRegistryPerson(partyId, personRef) {
  return json(await authFetch(`${PARTY}/individual/${partyId}/registryLink`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ personRef }),
  }));
}

export async function runRegistrySync() {
  return json(await authFetch(`${PARTY}/registrySync/run`, { method: 'POST' }));
}

export async function directorySettingsOf(partyId) {
  try {
    return await json(await authFetch(`${PARTY}/individual/${partyId}/directorySetting`));
  } catch { return []; }
}

/** Upsert by (party, serviceRef): {serviceRef?, exposure?, secretNumber?}. */
export async function saveDirectorySetting(partyId, body) {
  return json(await authFetch(`${PARTY}/individual/${partyId}/directorySetting`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }));
}

export async function runDirectoryExport() {
  return json(await authFetch(`${PARTY}/directoryExport/run`, { method: 'POST' }));
}

export async function directoryExportRun(runId) {
  return json(await authFetch(`${PARTY}/directoryExport/${encodeURIComponent(runId)}`));
}

export async function aiNextBestOffer(partyId) {
  const res = await authFetch('/ai/v1/nextBestOffer', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ partyId }),
  });
  if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || `HTTP ${res.status}`);
  return res.json();
}

/* ---- the workspace: universal search lookups, a message to the customer, a ticket by id ---- */

/** A free-text message to the customer's inbox (and email where the tenant sends it) — lands on the timeline too. */
export async function sendMessage(customerId, subject, content) {
  return json(await authFetch('/tmf-api/communicationManagement/v4/communicationMessage', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      subject, content,
      relatedParty: [{ id: customerId, role: 'customer', '@referredType': 'Individual' }],
    }),
  }));
}

export async function getTicket(id) {
  const res = await authFetch(`${TICKET}/troubleTicket/${encodeURIComponent(id)}`);
  return res.ok ? res.json() : null;
}

export async function orderById(id) {
  const res = await authFetch(`${ORDERING}/productOrder/${encodeURIComponent(id)}`);
  return res.ok ? res.json() : null;
}

export async function productById(id) {
  const res = await authFetch(`${INVENTORY}/product/${encodeURIComponent(id)}`);
  return res.ok ? res.json() : null;
}

/** Any identifier the caller may read out — a party id, an order, a ticket, a product — resolved to the customer behind it. */
export async function customerByIdentifier(idLike) {
  const id = idLike.trim();
  const partyOf = (obj) => (obj?.relatedParty || []).find((p) => p.role === 'customer')?.id || (obj?.relatedParty || [])[0]?.id;
  const direct = await authFetch(`${PARTY}/individual/${encodeURIComponent(id)}`);
  if (direct.ok) return { customer: await direct.json(), via: 'customer id' };
  for (const [via, fn] of [['order', orderById], ['ticket', getTicket], ['product', productById]]) {
    const obj = await fn(id).catch(() => null);
    const pid = partyOf(obj);
    if (pid) {
      const c = await authFetch(`${PARTY}/individual/${encodeURIComponent(pid)}`);
      if (c.ok) return { customer: await c.json(), via: `${via} ${id.slice(0, 8)}…` };
    }
  }
  return null;
}

/* ---- the loop: recommendation outcomes, live intent on chat, the after-call note ---- */

/** Tell the ontology what became of a recommendation it made (accepted, dismissed, helpful, unhelpful). */
export async function recommendationOutcome(decisionId, outcome, reason) {
  return json(await authFetch(`/ontology/v1/context/recommendations/${encodeURIComponent(decisionId)}/outcome`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ outcome, ...(reason ? { reason } : {}) }),
  }));
}

export async function aiChatIntent(messages) {
  return json(await authFetch('/ai/v1/chatIntent', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ messages: messages.slice(-12).map((m) => ({ author: m.author, body: m.body })) }),
  }));
}

export async function aiWrapUp(record) {
  return json(await authFetch('/ai/v1/wrapUp', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(record),
  }));
}
