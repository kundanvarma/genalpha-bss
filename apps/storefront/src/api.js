/*
 * TMF Open API client, same-origin through the gateway. The backends enforce
 * party scoping ("my orders", "my products"); this client never filters by
 * party itself.
 */
import { authFetch, publicFetch, tokenClaims } from './auth.js';

const CATALOG = '/tmf-api/productCatalogManagement/v4';
const GEO = '/tmf-api/geographicAddressManagement/v4';
const ORDERING = '/tmf-api/productOrderingManagement/v4';
const INVENTORY = '/tmf-api/productInventory/v4';
const PARTY = '/tmf-api/party/v4';
const STOCK = '/tmf-api/productStockManagement/v4';
const PAYMENT = '/tmf-api/paymentManagement/v4';

async function json(res) {
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

export async function listOfferings() {
  // L2 preview: staff append ?preview=1 to walk the unlaunched shelf —
  // the SERVER decides what a token may see; guests get Active regardless.
  // The API caps a page at 100 and the shelf outgrew that — page through
  // with offset until a short page (same lesson as priceIndex).
  const preview = new URLSearchParams(window.location.search).get('preview') === '1';
  const fetcher = preview ? authFetch : publicFetch;
  const filter = preview ? '' : '&lifecycleStatus=Active';
  const all = [];
  for (let offset = 0; ; offset += 100) {
    const page = await json(await fetcher(
      `${CATALOG}/productOffering?limit=100&offset=${offset}${filter}`));
    if (!Array.isArray(page)) break;
    all.push(...page);
    if (page.length < 100) break;
  }
  return all;
}

export async function getOffering(id) {
  return json(await publicFetch(`${CATALOG}/productOffering/${id}`));
}

export async function getSpec(id) {
  return json(await publicFetch(`${CATALOG}/productSpecification/${id}`));
}

/**
 * Units available for an offering, or null when it is not stock-managed
 * (services and subscriptions have no shelf).
 */
export async function availabilityFor(offeringId) {
  // Composable deployment: no stock component means nothing is
  // stock-managed — same as an offering without a stock row.
  try {
    const rows = await json(await publicFetch(`${STOCK}/productStock?productOfferingId=${offeringId}`));
    if (!rows.length) return null;
    return rows.reduce((sum, r) => sum + (r.availableQuantity?.amount ?? 0), 0);
  } catch {
    return null;
  }
}

/** All active prices indexed by id, so offering price refs resolve locally.
 * The API caps a page at 100 and the price book outgrew that — page through
 * with offset until a short page, or offerings silently lose their prices. */
export async function priceIndex() {
  const index = {};
  for (let offset = 0; ; offset += 100) {
    const page = await json(await publicFetch(
      `${CATALOG}/productOfferingPrice?limit=100&offset=${offset}`));
    for (const p of page) index[p.id] = p;
    if (!Array.isArray(page) || page.length < 100) break;
  }
  return index;
}

/**
 * First sign-in provisions the TMF632 individual; the backend keys it to the
 * token subject, so repeating this is a no-op.
 */
export async function ensureParty() {
  const claims = tokenClaims();
  const email = claims.email || claims.preferred_username;
  return json(await authFetch(`${PARTY}/individual`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      givenName: claims.given_name || claims.preferred_username || 'Customer',
      familyName: claims.family_name || '—',
      // The email rides along so assisted channels can identify the customer
      // by something a human recognizes, not a UUID.
      ...(email && email.includes('@') ? {
        contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }],
      } : {}),
    }),
  }));
}

export async function myParty() {
  const claims = tokenClaims();
  return json(await authFetch(`${PARTY}/individual/${claims.sub}`));
}

/** Registry-verify a delivery address for the signed-in shopper (freg F-P2):
 * TMF673 validation with the party context — the backend asks the delivery
 * country's national registry and answers match / mismatch / no_data /
 * unavailable. Returns the registryMatch part, or null (postal-wash only —
 * e.g. no registry for that country, or the address failed validation). */
export async function verifyDeliveryAddress(address, partyName) {
  const result = await json(await authFetch(`${GEO}/geographicAddressValidation`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ submittedGeographicAddress: address, relatedParty: { name: partyName } }),
  }));
  return result.registryMatch || null;
}

// ---------------- household billing (person-payer, with consent) ----------------

export async function myHousehold() {
  const claims = tokenClaims();
  return json(await authFetch(`${PARTY}/individual/${claims.sub}/household`));
}

export async function requestHouseholdPayer(payerEmail) {
  const claims = tokenClaims();
  return json(await authFetch(`${PARTY}/individual/${claims.sub}/householdPayer`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ payerEmail }),
  }));
}

/** The payer invites an EXISTING customer into the family — the member
 * accepts (or not) from their own Family page. Consent, mirrored. */
export async function inviteFamilyMember(memberEmail) {
  return json(await authFetch(`${PARTY}/individual/${tokenClaims().sub}/householdInvite`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ memberEmail }),
  }));
}

export async function acceptFamilyInvite() {
  return json(await authFetch(`${PARTY}/individual/${tokenClaims().sub}/householdInvite/accept`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }));
}

export async function acceptDependent(dependentId) {
  return json(await authFetch(`${PARTY}/individual/${dependentId}/householdPayer/accept`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }));
}

export async function endHouseholdLink(dependentId) {
  return json(await authFetch(`${PARTY}/individual/${dependentId}/householdPayer`, {
    method: 'DELETE',
  }));
}

/** CHILD ACCOUNT: the payer mints the kid's login (TMF672, customer role
 * only) and creates their party record with the household link born active.
 * The temporary password is returned ONCE, for hand-over. */
export async function addFamilyMember(givenName, familyName, email, birthDate) {
  const login = await json(await authFetch('/tmf-api/rolesAndPermissionsManagement/v4/user', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, givenName, familyName }),
  }));
  const claims = tokenClaims();
  await json(await authFetch(`${PARTY}/individual/${claims.sub}/dependents`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      id: login.id, givenName, familyName,
      ...(birthDate ? { birthDate } : {}),
      contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }],
    }),
  }));
  return { id: login.id, email, temporaryPassword: login.temporaryPassword };
}

/** The payer orders FOR a dependent — ordering verifies the live link and
 * stamps the payer; the plan lands on the payer's bill. */
export async function orderForDependent(offering, dependentId) {
  return json(await authFetch(`${ORDERING}/productOrder`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      productOrderItem: [{ action: 'add',
        productOffering: { id: offering.id, name: offering.name } }],
      relatedParty: [{ id: dependentId, role: 'customer' }],
    }),
  }));
}

export async function updateMyParty(patch) {
  const claims = tokenClaims();
  return json(await authFetch(`${PARTY}/individual/${claims.sub}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  }));
}

const COMM = '/tmf-api/communicationManagement/v4';

/** My marketing preference — { marketingOptOut }. */
export async function myMarketingPreference() {
  return json(await authFetch(`${COMM}/marketingPreference`));
}

/** Set my marketing preference; opting out stops marketing (in-app + email). */
export async function setMarketingPreference(optOut) {
  return json(await authFetch(`${COMM}/marketingPreference`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ optOut }),
  }));
}

/**
 * Authorizes the one-time charges with the payment service (mock PSP in dev).
 * Card details go straight to the API and are never stored client-side.
 * A decline surfaces as an Error with the PSP's reason.
 */
export async function createPayment(amount, card, description) {
  return json(await authFetch(`${PAYMENT}/payment`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      description,
      amount: { unit: amount.unit, value: amount.value },
      paymentMethod: { '@type': 'bankCard', ...card },
    }),
  }));
}

/**
 * One TMF622 order for the whole cart: a top-level item per cart line with
 * its quantity, and a configured bundle's choices (phone, color, storage) as
 * nested productOrderItem children carrying product.productCharacteristic.
 * `shippingPlace` (a GeographicAddress) rides every physical item — callers
 * mark lines/selections physical via the stock service.
 */
export async function checkoutCart(lines, shippingPlace = null, paymentRefs = null, promotionCode = null) {
  const productFor = (characteristics, physical) => {
    const product = {};
    if (characteristics && Object.keys(characteristics).length) {
      product.productCharacteristic = Object.entries(characteristics)
        .map(([name, value]) => ({ name, value }));
    }
    if (physical && shippingPlace) {
      product.place = [shippingPlace];
    }
    return Object.keys(product).length ? { product } : {};
  };
  const items = lines.map((line, i) => ({
    id: String(i + 1),
    action: 'add',
    quantity: line.quantity,
    productOffering: { id: line.offeringId, name: line.name, '@referredType': 'ProductOffering' },
    ...productFor(line.characteristics || null, line.physical),
    ...(line.selections?.length ? {
      productOrderItem: line.selections.map((s, j) => ({
        id: `${i + 1}.${j + 1}`,
        action: 'add',
        quantity: line.quantity,
        productOffering: { id: s.offeringId, name: s.name, '@referredType': 'ProductOffering' },
        ...productFor(s.characteristics, s.physical),
      })),
    } : {}),
  }));
  const description = lines
    .map((l) => l.quantity > 1 ? `${l.name} ×${l.quantity}` : l.name)
    .join(', ');
  return json(await authFetch(`${ORDERING}/productOrder`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      description,
      productOrderItem: items,
      ...(promotionCode ? { promotionCode } : {}),
      ...(paymentRefs?.length ? { payment: paymentRefs } : {}),
    }),
  }));
}

export async function myOrders() {
  return json(await authFetch(`${ORDERING}/productOrder?limit=100`));
}

export async function cancelOrder(id) {
  return json(await authFetch(`${ORDERING}/productOrder/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ state: 'cancelled' }),
  }));
}

export async function myProducts() {
  return json(await authFetch(`${INVENTORY}/product?limit=100`));
}

// Track-my-delivery: the customer's own shipping orders (party-scoped by the
// fulfilment service). Each carries a carrier trackingRef once dispatched.
const SHIPPING = '/tmf-api/shippingOrderManagement/v4';
export async function myShipments() {
  return json(await authFetch(`${SHIPPING}/shippingOrder`));
}

// The order JOURNEY (TMF701): the milestones + a plain-language "why it's in
// progress", so a customer sees the whole story and never has to phone support.
const PROCESS = '/tmf-api/processFlowManagement/v4';
export async function myOrderJourney(orderId) {
  try {
    const flows = await json(await authFetch(`${PROCESS}/processFlow?productOrderId=${orderId}`));
    if (!flows.length) return null;
    return await json(await authFetch(`${PROCESS}/processFlow/${flows[0].id}`));
  } catch { return null; }
}

const PAY = '/tmf-api/paymentManagement/v4';
/** The payment methods this tenant offers (card + redirect/BNPL) — anonymous. */
export async function paymentMethods() {
  try {
    return await json(await publicFetch(`${PAY}/payment/methods`));
  } catch {
    return [{ method: 'card', redirect: false }];
  }
}
/** Open a redirect/BNPL session (Klarna) — returns where to send the customer. */
export async function createPaymentSession(dto) {
  return json(await authFetch(`${PAY}/payment/session`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(dto) }));
}
/** Confirm a redirect session on return → the authorized payment (idempotent). */
export async function confirmPayment(provider, sessionId) {
  return json(await authFetch(`${PAY}/payment/confirm`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider, sessionId }) }));
}

/** The shopper's delivery menu for a postcode (home + pickup points) — anonymous,
 * so guest checkout can offer it. Fail-soft to [] (outage / no carrier menu). */
export async function deliveryOptions(postCode) {
  if (!postCode) return [];
  try {
    return await json(await publicFetch(`${SHIPPING}/carrier/deliveryOptions?postcode=${encodeURIComponent(postCode)}`));
  } catch {
    return [];
  }
}

/** A family member's products, through the household link: inventory verifies
 * the caller is their payer or a family admin, live at the party source. */
export async function memberProducts(memberId) {
  return json(await authFetch(`${INVENTORY}/product?relatedPartyId=${encodeURIComponent(memberId)}&limit=100`));
}

/** Owner-only: promote a member to family admin, or demote back. */
export async function setFamilyRole(memberId, role) {
  return json(await authFetch(`${PARTY}/individual/${memberId}/householdRole`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role }),
  }));
}

/** The SIM behind a line; null when none is on file (pre-SIM activations). */
export async function mySim(serviceId, reveal = false) {
  const res = await authFetch(`${SERVICE_INV}/service/${serviceId}/sim${reveal ? '?reveal=true' : ''}`);
  if (!res.ok) return null;
  return res.json();
}

/** "It feels slow": triage before ticket — outage? out of data? paused? */
export async function diagnoseMyService(serviceId) {
  return json(await authFetch(`${SERVICE_INV}/service/${serviceId}/diagnose`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }));
}

/** Cancel the subscription — the number is released. Keeping it means
 * your NEW operator ports it first; cancel after, never before. */
export async function cancelMyService(serviceId) {
  return json(await authFetch(`${SERVICE_INV}/service/${serviceId}/terminate`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason: 'cancelled by customer' }),
  }));
}

/** Going away? Pause the line — your number and SIM stay yours, and the
 * hold lifts itself on the day you pick. */
export async function pauseMyService(serviceId, days) {
  return json(await authFetch(`${SERVICE_INV}/service/${serviceId}/suspend`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(days ? { days } : {}),
  }));
}

export async function resumeMyService(serviceId) {
  return json(await authFetch(`${SERVICE_INV}/service/${serviceId}/resume`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }));
}

/** Lost your SIM? Block the old card and get a fresh one on the same
 * number — you will be notified, and the new PUK is revealable as usual. */
export async function replaceMySim(serviceId, reason) {
  return json(await authFetch(`${SERVICE_INV}/service/${serviceId}/sim/replace`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  }));
}

/** OTA PIN change — pushed to the card via the operator's SIM platform. */
export async function resetSimPin(serviceId, newPin) {
  return json(await authFetch(`${SERVICE_INV}/service/${serviceId}/sim/resetPin`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ newPin }),
  }));
}

/** One-tap purchase for simple digital items (data top-ups): a bare add order. */
export async function quickOrder(offering) {
  return json(await authFetch(`${ORDERING}/productOrder`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      productOrderItem: [{ action: 'add', productOffering: { id: offering.id, name: offering.name } }],
    }),
  }));
}

/**
 * Plan change (TMF622 action=modify): same service, same number — only the
 * plan swaps. The realizing service rides along so the SOM renames the right
 * line. Completes instantly: no fulfilment, no new MSISDN.
 */
export async function changePlan(productId, serviceId, newOffering) {
  return json(await authFetch(`${ORDERING}/productOrder`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      productOrderItem: [{
        action: 'modify',
        product: { id: productId, ...(serviceId ? { realizingService: [{ id: serviceId }] } : {}) },
        productOffering: { id: newOffering.id, name: newOffering.name },
      }],
    }),
  }));
}

const CONSUMPTION = '/tmf-api/usageConsumption/v4';
const AGREEMENT = '/tmf-api/agreementManagement/v4';

const PROMOTION = '/tmf-api/promotionManagement/v4';
const ADDRESS = '/tmf-api/geographicAddressManagement/v4';

/** TMF673 anonymous validation: normalized address or the reason it fails. */
export async function validateAddress(address) {
  return json(await publicFetch(`${ADDRESS}/geographicAddressValidation`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ submittedGeographicAddress: address }),
  }));
}

/** Anonymous promo-code check — the shop window prices the discount. */
export async function checkPromotion(code) {
  return json(await publicFetch(`${PROMOTION}/checkPromotion`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code }),
  }));
}

const RECOMMENDATION = '/tmf-api/recommendationManagement/v4';
const PAYMENT_METHODS = '/tmf-api/paymentMethods/v4';

export async function myPaymentMethods() {
  return json(await authFetch(`${PAYMENT_METHODS}/paymentMethod`));
}

/** Vault a card's PRESENTATION data (brand guess, last4, expiry) — never the PAN. */
export async function savePaymentMethod(cardNumber, expiry) {
  const digits = cardNumber.replace(/\s/g, '');
  return json(await authFetch(`${PAYMENT_METHODS}/paymentMethod`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      '@type': 'bankCard',
      details: {
        brand: digits.startsWith('4') ? 'visa' : digits.startsWith('5') ? 'mastercard' : 'card',
        lastFourDigits: digits.slice(-4),
        expiry,
      },
    }),
  }));
}

/** Pay with a vaulted method: the API resolves the token server-side. */
export async function paymentWithSavedMethod(amount, methodId, description) {
  return json(await authFetch(`${PAYMENT}/payment`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      description,
      amount: { unit: amount.unit, value: amount.value },
      paymentMethod: { '@type': 'savedPaymentMethod', id: methodId },
    }),
  }));
}


export async function myRecommendations() {
  return json(await authFetch(`${RECOMMENDATION}/recommendation`));
}

/** The individualized shop: MY rail — ranked, captioned, churn-aware.
 * Self-scoped server-side (party = my token), fail-soft to nothing. */
export async function forYou() {
  const res = await authFetch('/ai/v1/forYou');
  return res.ok ? res.json() : null;
}

/** "Customers who bought this also bought" — item-to-item affinity,
 * PUBLIC (guests see it on the product page), fail-soft to []. */
export async function alsoBought(offeringId) {
  const res = await publicFetch(`${RECOMMENDATION}/affinity?forOfferingId=${offeringId}`);
  return res.ok ? res.json() : [];
}

export async function myAgreements() {
  return json(await authFetch(`${AGREEMENT}/agreement?limit=100`));
}

export async function myUsage() {
  return json(await authFetch(`${CONSUMPTION}/queryUsageConsumption`));
}

/** Gift remaining GB — to a family member by id, or straight to a phone
 * number when the plan's giftScope reaches that far; usage verifies the
 * link (or resolves the number in the tenant's own pool) live. */
const LOYALTY = '/tmf-api/loyaltyManagement/v4';
const CAMPAIGN = '/tmf-api/campaignManagement/v4';

/** G1 — my referral code (minted on first ask) + its tally. */
export async function myReferral() {
  return json(await authFetch(`${CAMPAIGN}/referral/myCode`));
}

/** Redeem a friend's code — pays both sides on your first completed order. */
export async function redeemReferral(code) {
  return json(await authFetch(`${CAMPAIGN}/referral/redeem`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code }) }));
}


/** Loyalty: opt-in membership, my balance, and points→GB redemption. */
export async function myLoyalty() {
  const res = await authFetch(`${LOYALTY}/loyaltyProgramMember/me`);
  if (res.status === 404) return null; // not a member (or no program)
  return json(res);
}
export async function enrollLoyalty() {
  return json(await authFetch(`${LOYALTY}/loyaltyProgramMember`, { method: 'POST' }));
}
export async function loyaltyProgram() {
  const res = await authFetch(`${LOYALTY}/loyaltyProgram`);
  if (res.status === 404) return null;
  return json(res);
}
export async function redeemLoyaltyVoucher() {
  return json(await authFetch(`${LOYALTY}/redeem`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 'voucher' }),
  }));
}
export async function redeemLoyaltyData(gb) {
  return json(await authFetch(`${LOYALTY}/redeem`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 'data', gb }),
  }));
}

export async function giftData(receiver, amount) {
  return json(await authFetch('/tmf-api/usageManagement/v4/gift', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...receiver, amount }),
  }));
}

/** Owner or admin: the family's monthly top-up budget for a member (EUR). */
export async function setAllowance(memberId, monthlyValue) {
  return json(await authFetch(`${PARTY}/individual/${memberId}/allowance`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ monthlyValue }),
  }));
}

/** Held ask-to-buy orders across my family — the hub's approvals inbox. */
export async function familyApprovals() {
  return json(await authFetch(`${ORDERING}/productOrder/familyApprovals`));
}

/** A CHILD's usage meters, through the household link (payer/admin only —
 * an adult member's usage stays their own even when the family pays). */
export async function memberUsage(memberId) {
  return json(await authFetch(`${CONSUMPTION}/queryUsageConsumption?relatedPartyId=${encodeURIComponent(memberId)}`));
}

/** The FAQ library — the customer shelf of the knowledge base. The audience
 *  filter is what keeps the CSR/sales/product-owner cheat-sheets off the
 *  customer's Support page: the shop only ever asks for customer-facing articles. */
export async function searchFaq(q) {
  const params = new URLSearchParams({ audience: 'customer' });
  if (q) params.set('q', q);
  return json(await authFetch(`/tmf-api/knowledgeManagement/v4/article?${params}`));
}

export async function decideApproval(orderId, approve) {
  return json(await authFetch(`${ORDERING}/productOrder/${orderId}/approval`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ approve }),
  }));
}

const BILLING = '/tmf-api/customerBillManagement/v4';

/** Credit notes: the numbered documents that reversed (part of) a bill. */
export async function myCreditNotes() {
  const res = await authFetch(`${BILLING}/creditNote`);
  if (!res.ok) return [];
  return res.json();
}

export async function myBills() {
  return json(await authFetch(`${BILLING}/customerBill?limit=100`));
}

export async function billRates(billId) {
  return json(await authFetch(`${BILLING}/customerBill/${billId}/appliedCustomerBillingRate`));
}

/** Collections: the customer's OWN case (party-scoped by the backend) —
 * null when the account stands current, or the collections module is absent
 * (fail-soft, like every optional component). */
export async function myCollectionCase() {
  try {
    const cases = await json(await authFetch(`${BILLING}/collectionCase`));
    return Array.isArray(cases) && cases.length ? cases[0] : null;
  } catch { return null; }
}

/** "I will pay by then" — the customer's own promise-to-pay pauses the
 * dunning ladder within the policy allowance. Amount defaults to the full
 * overdue balance on the backend. */
export async function promiseToPay(caseId, days) {
  return json(await authFetch(`${BILLING}/collectionCase/${caseId}/promiseToPay`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(days ? { days: Number(days) } : {}),
  }));
}

const QUALIFICATION = '/tmf-api/productOfferingQualification/v4';
const APPOINTMENT = '/tmf-api/appointment/v4';

/**
 * TMF679 serviceability check — anonymous shop-window functionality.
 * items: [{offeringId, name}]; every item is checked against the place.
 */
export async function checkQualification(items, place) {
  // Composable deployment: no qualification component means nothing is
  // serviceability-gated.
  try {
    return await checkQualificationStrict(items, place);
  } catch {
    return { productOfferingQualificationItem: [] };
  }
}

async function checkQualificationStrict(items, place) {
  return json(await publicFetch(`${QUALIFICATION}/checkProductOfferingQualification`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      productOfferingQualificationItem: items.map((item, i) => ({
        id: String(i + 1),
        productOffering: { id: item.offeringId, name: item.name, '@referredType': 'ProductOffering' },
        place,
      })),
    }),
  }));
}

/** TMF645 technical footprint: what the network can DELIVER at this place —
 * technology and bandwidth, anonymous like the commercial check above.
 * Composable: no qualification component means no footprint to show. */
export async function queryServiceQualification(place) {
  try {
    return json(await publicFetch(
      '/tmf-api/serviceQualificationManagement/v4/queryServiceQualification', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ searchCriteria: { place } }),
      }));
  } catch {
    return { serviceQualificationItem: [] };
  }
}

/** TMF646 free installer slots — also anonymous. The search says WHERE (the
 * install address as relatedPlace) and FOR WHAT (the gated offerings as
 * relatedEntity), so a tenant's own workforce system can answer by zone and
 * skill; the built-in roster ignores what it does not use. */
export async function searchTimeSlots({ place, offerings } = {}) {
  const body = { '@type': 'SearchTimeSlot' };
  if (place) body.relatedPlace = { role: 'installation', ...place, '@type': 'GeographicAddress' };
  if (offerings?.length) {
    body.relatedEntity = offerings.map((o) => ({ id: o.id, name: o.name, '@referredType': 'ProductOffering' }));
  }
  try {
    return await json(await publicFetch(`${APPOINTMENT}/searchTimeSlot`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }));
  } catch {
    return { availableTimeSlot: [] };
  }
}

export async function createAppointment(slot, orderId, place, description) {
  return json(await authFetch(`${APPOINTMENT}/appointment`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      validFor: slot,
      description,
      relatedEntity: [{ id: orderId, '@referredType': 'ProductOrder' }],
      place,
    }),
  }));
}

export async function myAppointments() {
  return json(await authFetch(`${APPOINTMENT}/appointment?limit=100`));
}

/** Settle a bill with an authorized payment; billing captures it. */
/** PAY IN PARTS: split an unpaid bill into monthly installments. */
export async function splitBill(billId, installments) {
  return json(await authFetch(`${BILLING}/customerBill/${billId}/installmentPlan`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ installments }),
  }));
}

export async function payInstallment(billId, paymentRef) {
  return json(await authFetch(`${BILLING}/customerBill/${billId}/installmentPlan/pay`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ payment: [paymentRef] }),
  }));
}

/** Payday alignment: pick the day (1-28) your billing cycle starts. */
export async function setBillingDay(anchorDay) {
  return json(await authFetch(`${PARTY}/individual/${tokenClaims().sub}/billingCycle`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ anchorDay }),
  }));
}

/** How your bill arrives: paper, einvoice, digital — or default. */
export async function setBillDelivery(preference) {
  return json(await authFetch(`${PARTY}/individual/${tokenClaims().sub}/billDelivery`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ preference }),
  }));
}

/** "This charge is wrong": open a dispute — collection pauses while we look. */
export async function disputeBill(billId, reason) {
  return json(await authFetch(`${BILLING}/customerBill/${billId}/dispute`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  }));
}

export async function settleBill(billId, paymentRef) {
  return json(await authFetch(`${BILLING}/customerBill/${billId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ state: 'settled', payment: [paymentRef] }),
  }));
}

const TICKET = '/tmf-api/troubleTicket/v4';

export async function myTickets() {
  return json(await authFetch(`${TICKET}/troubleTicket?limit=100`));
}

export async function raiseTicket(name, description) {
  return json(await authFetch(`${TICKET}/troubleTicket`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, description, severity: 'minor', ticketType: 'support' }),
  }));
}

/** Customers may close a ticket once an agent has resolved it. */
export async function closeTicket(id) {
  return json(await authFetch(`${TICKET}/troubleTicket/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status: 'closed' }),
  }));
}

const COMMUNICATION = '/tmf-api/communicationManagement/v4';

export async function myNotifications() {
  return json(await authFetch(`${COMMUNICATION}/communicationMessage?limit=100`));
}

export async function markNotificationRead(id) {
  return json(await authFetch(`${COMMUNICATION}/communicationMessage/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status: 'read' }),
  }));
}

// Number porting (keep your number). Fail-soft: if the porting component
// is not deployed, checkout proceeds and the customer gets a new number.
const PORTING = '/tmf-api/numberPortingManagement/v1';

function countryOf(number) {
  const n = (number || '').replace(/\s/g, '');
  if (n.startsWith('+47')) return 'NO';
  if (n.startsWith('+46')) return 'SE';
  if (n.startsWith('+45')) return 'DK';
  if (n.startsWith('+358')) return 'FI';
  if (n.startsWith('+592')) return 'GY';
  if (n.startsWith('+44')) return 'GB';
  if (n.startsWith('+1')) return 'US';
  // no prefix given: the shopper is porting within the operator's own country
  return (window.BSS_STOREFRONT_CONFIG || {}).country || 'NO';
}

/** Choose-your-number: a shortlist of available numbers from the pool —
 * previewed, never consumed; a new shuffle deals a fresh hand. Fail-soft []. */
export async function numberOffers(shuffle = null) {
  try {
    const q = shuffle ? `?shuffle=${encodeURIComponent(shuffle)}` : '';
    return await json(await publicFetch(`/tmf-api/resourcePoolManagement/v4/numberOffer${q}`));
  } catch { return []; }
}

export async function requestPortIn(partyId, number, currentProvider, portDate = null) {
  const created = await json(await authFetch(`${PORTING}/numberPortingOrder`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      direction: 'portIn', phoneNumber: number, country: countryOf(number),
      otherOperator: currentProvider, relatedParty: [{ id: partyId, role: 'customer' }],
      // the customer's wish date — a morning window on the chosen day
      ...(portDate ? { requestedCutover: `${portDate}T08:00:00Z` } : {}),
    }),
  }));
  // Dev: an AS-SOON-AS-POSSIBLE cutover is compressed to the checkout;
  // a FUTURE-DATED port stays scheduled until its window — the date the
  // customer picked is a promise, not a decoration. Production always waits
  // for the clearinghouse's agreed window.
  if (created.status === 'scheduled' && !portDate) {
    await authFetch(`${PORTING}/numberPortingOrder/${created.id}/complete`, { method: 'POST' })
      .catch(() => {});
  }
  return created;
}

// The running services from the orchestrator's inventory — carries the
// active number (drawn from the pool, or the one the customer ported in).
const SERVICE_INV = '/tmf-api/serviceInventory/v4';
export async function myActiveServices() {
  try {
    return await json(await authFetch(`${SERVICE_INV}/service`));
  } catch { return []; }
}

/**
 * Dynamic pricing preview: ask the policy component what the enabled pricing
 * rules do to this subtotal. Fail-soft — an outage or a guest session simply
 * means no preview, and the bill remains the source of truth.
 */
export async function previewPrice(subtotal, offeringIds, characteristicValues = []) {
  try {
    const res = await authFetch('/tmf-api/policyManagement/v4/price', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      // characteristicValues ("color:Icy Blue") let marketing run a
      // campaign on a colour — a pricing rule conditioned on the pick
      body: JSON.stringify({ context: { subtotal, offeringIds, characteristicValues, channel: 'shop' } }),
    });
    if (!res.ok) return null;
    const result = await res.json();
    return (result.adjustments || []).length ? result : null;
  } catch { return null; }
}

// ---------------- customer insight (first-party, consent first) ----------------

const INSIGHT = '/insight/v1';
const VISITOR_KEY = 'bss.shop.visitor';
const CONSENT_KEY = 'bss.shop.consent';

/** TMF699: a prospect knocks — no account, no token, just interest. */
export async function submitSalesLead(lead) {
  const res = await publicFetch('/tmf-api/salesManagement/v4/salesLead', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(lead),
  });
  if (!res.ok) throw new Error('Could not reach sales right now — please try again.');
  return res.json();
}

export function visitorId() {
  let id = localStorage.getItem(VISITOR_KEY);
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem(VISITOR_KEY, id);
  }
  return id;
}

export function consentChoice() {
  try { return JSON.parse(localStorage.getItem(CONSENT_KEY)); } catch { return null; }
}

export async function saveConsent(analytics, personalization) {
  localStorage.setItem(CONSENT_KEY, JSON.stringify({ analytics, personalization }));
  // the choice MUST land server-side (it gates every write there) — retry
  // through a cold start rather than silently losing it
  const send = () => publicFetch(`${INSIGHT}/consent`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ visitorId: visitorId(), analytics, personalization }),
  }).then((r) => { if (!r.ok) throw new Error(String(r.status)); });
  for (let attempt = 0; attempt < 3; attempt++) {
    try { await send(); return; } catch {
      await new Promise((r) => setTimeout(r, 1200 * (attempt + 1)));
    }
  }
}

/** Fire-and-forget breadcrumb — the server drops it without consent anyway;
 * the client also holds back, out of politeness. */
export function beacon(type, category, offeringId) {
  if (!consentChoice()?.analytics) return;
  publicFetch(`${INSIGHT}/event`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ visitorId: visitorId(), type, category, offeringId,
      utmSource: new URLSearchParams(location.search).get('utm_source') || undefined }),
  }).catch(() => {});
}

export async function myExperience() {
  if (!consentChoice()?.personalization) return { personalized: false };
  const res = await publicFetch(`${INSIGHT}/experience?visitorId=${visitorId()}`);
  return res.ok ? res.json() : { personalized: false };
}

// ---------------- device commerce (trade-in, financing, withdrawal) ----------------

const DEVICE = '/tmf-api/deviceCommerce/v1';

/** IMEI + guided condition answers → a live estimate off the residual table. */
export async function quoteTradeIn(dto) {
  return json(await authFetch(`${DEVICE}/tradeInValuation`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...dto, channel: 'shop' }),
  }));
}

export async function acceptTradeIn(id) {
  return json(await authFetch(`${DEVICE}/tradeInValuation/${id}/accept`, { method: 'POST' }));
}

/** My trade-ins — quoted, mailed in, graded, settled. Party-scoped server-side. */
export async function myTradeIns() {
  return json(await authFetch(`${DEVICE}/tradeInValuation`));
}

/** The grading came back LOWER — take the revised value, or refuse and get
 * the device back. Money never moves without the customer's word. */
export async function acceptTradeInRevaluation(id) {
  return json(await authFetch(`${DEVICE}/tradeInValuation/${id}/acceptRevaluation`, { method: 'POST' }));
}

export async function rejectTradeInRevaluation(id) {
  return json(await authFetch(`${DEVICE}/tradeInValuation/${id}/rejectRevaluation`, { method: 'POST' }));
}

/** The checkout chooser face: per-model monthly + TOTAL cost, before signing. */
export async function financingQuote(principal, termMonths, financingModel) {
  return json(await authFetch(`${DEVICE}/financingQuote`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ principal, termMonths, financingModel }),
  }));
}

/** Sign the device financing agreement (instalments / pay-later) post-order. */
export async function createDeviceAgreement(dto) {
  return json(await authFetch(`${DEVICE}/deviceAgreement`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  }));
}

export async function myDeviceAgreements() {
  return json(await authFetch(`${DEVICE}/deviceAgreement`));
}

export async function deviceUpgradeEligibility(agreementId) {
  return json(await authFetch(`${DEVICE}/deviceAgreement/${agreementId}/upgradeEligibility`));
}

/** The 14-day withdrawal (angrerett): unconditional inside the window. */
export async function openDeviceWithdrawal(agreementId) {
  return json(await authFetch(`${DEVICE}/withdrawalCase`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ agreementId }),
  }));
}

// ---------------- usage policy (pool, spend meters, auto top-up) ----------------

const USAGE_MGMT = '/tmf-api/usageManagement/v4';

/** The household data pools I own or draw — members ride along for managers. */
export async function myAllowancePools() {
  return json(await authFetch(`${USAGE_MGMT}/allowancePool`));
}

export async function createAllowancePool(poolGB, name = null) {
  return json(await authFetch(`${USAGE_MGMT}/allowancePool`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ poolGB, ...(name ? { name } : {}) }),
  }));
}

export async function addPoolMember(poolId, partyId, softLimitGB = null, hardLimitGB = null) {
  return json(await authFetch(`${USAGE_MGMT}/allowancePool/${poolId}/member`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ partyId,
      ...(softLimitGB != null ? { softLimitGB } : {}),
      ...(hardLimitGB != null ? { hardLimitGB } : {}) }),
  }));
}

export async function patchPoolMember(poolId, partyId, patch) {
  return json(await authFetch(`${USAGE_MGMT}/allowancePool/${poolId}/member/${partyId}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  }));
}

export async function removePoolMember(poolId, partyId) {
  const res = await authFetch(`${USAGE_MGMT}/allowancePool/${poolId}/member/${partyId}`, {
    method: 'DELETE' });
  if (!res.ok) {
    const problem = await res.json().catch(() => ({}));
    throw new Error(problem.message || `HTTP ${res.status}`);
  }
}

/** My three spend-meter faces: spend cap, content services, roaming limit. */
export async function mySpendPolicy() {
  return json(await authFetch(`${USAGE_MGMT}/spendPolicy`));
}

export async function patchSpendMeter(meterType, dto) {
  return json(await authFetch(`${USAGE_MGMT}/spendPolicy/${meterType}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  }));
}

/** The audited "keep me roaming" election — service past the limit only on
 * the customer's explicit request (EU 2022/612). */
export async function roamingContinue() {
  return json(await authFetch(`${USAGE_MGMT}/roamingLimit/continue`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }));
}

export async function myAutoTopup() {
  return json(await authFetch(`${USAGE_MGMT}/autoTopupPolicy`));
}

/** PUT semantics — enabling REQUIRES consent:true in the same request. */
export async function setAutoTopup(dto) {
  return json(await authFetch(`${USAGE_MGMT}/autoTopupPolicy`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  }));
}

// ---------------- directory privacy (number-directory exposure) ----------------

/** My directory exposure choices (full / partial / reserved + secret number). */
export async function myDirectorySettings() {
  return json(await authFetch(`${PARTY}/individual/${tokenClaims().sub}/directorySetting`));
}

export async function setDirectorySetting(dto) {
  return json(await authFetch(`${PARTY}/individual/${tokenClaims().sub}/directorySetting`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  }));
}

/** On login: this browser's profile belongs to this customer now. */
export function stitchVisitor() {
  if (!consentChoice()?.personalization) return;
  authFetch(`${INSIGHT}/stitch`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ visitorId: visitorId() }),
  }).catch(() => {});
}
