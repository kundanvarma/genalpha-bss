'use strict';

/*
 * Back-office console: list / create / edit / delete over the TMF APIs
 * through the gateway (same origin — no CORS). Resources are configuration,
 * so extending the console is a data change, not new code.
 *
 * Field kinds: text (default), number, checkbox, money ({unit, value}),
 * ref (single entity reference), reflist (array of entity references),
 * select (static options), longtext, codepick (picklist whose value is a
 * plain attribute of the picked item, not a reference object).
 * Refs load their pick-lists from the API when the editor renders.
 *
 * Resource hooks: assemble(body) reshapes the flat editor output before it
 * is sent; rowAction gives a per-row verb beyond edit/delete (e.g. pause);
 * augmentRow(item, cell) fills a computed column after the row renders.
 */
'use strict';

// The host tenant's brand color themes the console, same as the customer channels.
const BRAND = window.BSS_CONSOLE_CONFIG || {};
if (BRAND.brandColor) {
  document.documentElement.style.setProperty('--teal', BRAND.brandColor);
  document.documentElement.style.setProperty('--teal-soft', BRAND.brandColor + '1F');
}
if (BRAND.brandName) document.title = `${BRAND.brandName} · back office`;

const API_BASE = '/tmf-api/productCatalogManagement/v4';
const STOCK_BASE = '/tmf-api/productStockManagement/v4';
const BILLING_BASE = '/tmf-api/customerBillManagement/v4';
const QUALIFICATION_BASE = '/tmf-api/productOfferingQualification/v4';
const APPOINTMENT_BASE = '/tmf-api/appointment/v4';
const CAMPAIGN_BASE = '/tmf-api/campaignManagement/v4';
const tenantCurrency = () => (window.BSS_CONSOLE_CONFIG || {}).currency || 'EUR';
const PROMOTION_BASE = '/tmf-api/promotionManagement/v4';
const POLICY_BASE = '/tmf-api/policyManagement/v4';
const REVENUE_BASE = '/revenue/v1';
const PROCESS_BASE = '/tmf-api/processFlowManagement/v4';
const KNOWLEDGE_BASE = '/tmf-api/knowledgeManagement/v4';
const PORTING_BASE = '/tmf-api/numberPortingManagement/v1';
const SALES_BASE = '/tmf-api/salesManagement/v4';
const ORDERING_BASE = '/tmf-api/productOrderingManagement/v4';
const ONBOARDING_BASE = '/onboarding/v1';
const ADVISOR_BASE = '/advisor/v1';
const SQM_BASE = '/tmf-api/serviceQualificationManagement/v4';
const RISK_BASE = '/tmf-api/riskManagement/v4';
const SERVICE_CATALOG_BASE = '/tmf-api/serviceCatalogManagement/v4';
const AGREEMENT_BASE = '/tmf-api/agreementManagement/v4';
const PARTNERSHIP_BASE = '/tmf-api/partnershipTypeManagement/v4';
const WHOLESALE_ORDER_BASE = '/tmf-api/serviceOrdering/v4';
const PAGE_SIZE = 10;
const REF_PICKLIST_LIMIT = 100;

// The business moments a campaign can react to (the event stream's editorial map).
const TRIGGER_EVENTS = [
  { value: 'ProductOrderCreateEvent', label: 'Order placed' },
  { value: 'ProductOrderStateChangeEvent', label: 'Order state changed' },
  { value: 'CustomerBillCreateEvent', label: 'Bill issued' },
  { value: 'TroubleTicketStateChangeEvent', label: 'Ticket state changed' },
  { value: 'ShoppingCartAbandonedEvent', label: 'Cart abandoned' },
  { value: 'AgreementCreateEvent', label: 'Agreement started' },
  { value: 'ChurnRiskDetectedEvent', label: 'Churn risk detected (AI scorer)' },
  { value: 'LoyaltyTierChangedEvent', label: 'Loyalty tier changed' },
  { value: 'IndividualCreateEvent', label: 'New customer registered (onboarding)' },
  { value: 'ShippingOrderStateChangeEvent', label: 'Parcel/handset shipped or delivered' },
  { value: 'BucketBalanceChangeEvent', label: 'Data balance topped up (top-up bought / loyalty data gifted)' },
  { value: 'UsageThresholdBreachedEvent', label: 'Data running low (OCS usage threshold, e.g. 80% used)' },
  { value: 'ServiceSliceChangeEvent', label: 'Network priority changed (boost pass on / lapsed, priority tier)' },
];

// Retention plays as one-click starting points: picking one prefills the
// form below (trigger + message) — everything stays editable before Save.
const CAMPAIGN_RECIPES = [
  { value: 'welcome', label: 'Onboarding: welcome a new customer the moment they register', fill: {
    name: 'Welcome — new customer',
    triggerEventType: 'IndividualCreateEvent',
    messageSubject: 'Welcome aboard!',
    messageContent: 'You\'re in — welcome! Your account is ready. Take a look at'
      + ' your My page to add services, and here\'s a little something to get'
      + ' started: attach a promo code above and {code} drops it into this'
      + ' message. (Tip: turn this into a multi-step Journey to greet on day 0,'
      + ' nudge to activate on day 3, and check in on day 7.)' } },
  { value: 'churn-save', label: 'Churn save: a loyalty offer to at-risk customers', fill: {
    name: 'Churn save — your points are waiting',
    triggerEventType: 'ChurnRiskDetectedEvent',
    messageSubject: 'A little thank-you from us',
    messageContent: 'We appreciate having you with us. Your loyalty points are'
      + ' waiting on your My page — redeem them for extra data this month or a'
      + ' discount voucher on anything in the shop. (Tip: attach a promo code'
      + ' above and {code} drops it into this message.)' } },
  { value: 'tier-congrats', label: 'Loyalty: congratulate a tier change', fill: {
    name: 'Tier congratulations',
    triggerEventType: 'LoyaltyTierChangedEvent',
    messageSubject: 'Your loyalty just moved you up',
    messageContent: 'Congratulations — your tier has changed! Tier benefits are'
      + ' applied automatically wherever you shop with us: member pricing shows'
      + ' at checkout and on your bill, no code needed.' } },
];

// The registered sales channels — the catalog rejects anything else, and the
// console never lets anyone type one: pickers only (no "webapp", no "mobileapp").
const CHANNELS = [
  { value: 'web', label: 'Web shop' }, { value: 'app', label: 'Mobile app' }, { value: 'store', label: 'Store / dealer' },
  { value: 'telesales', label: 'Telesales' }, { value: 'care', label: 'Care (assisted)' }, { value: 'business', label: 'Business console' },
  { value: 'partner', label: 'Partner portal' }, { value: 'agent-acp', label: 'AI agents via ACP' }, { value: 'agent-mcp', label: 'AI agents via MCP' },
  { value: 'agent-a2a', label: 'AI agents via A2A' },
];

// Presentation names for raw TMF field keys (fallback: the key itself).
const COLUMN_LABELS = {
  field: 'When the lead…', value: 'Matches', points: 'Points', minScore: 'From score', assignee: 'Handed to',
  lifecycleStatus: 'Status', isBundle: 'Bundle', lastUpdate: 'Updated',
  productOffering: 'Offering', stockedQuantity: 'Stocked', reservedQuantity: 'Reserved',
  availableQuantity: 'Available', billNo: 'Bill no', relatedParty: 'Customer',
  billingPeriod: 'Period', amountDue: 'Amount due', postcodePrefix: 'Postcode prefix',
  validFor: 'Window', triggerEventType: 'Trigger', promotionCode: 'Promo code',
  adjustmentValue: 'Adjustment', priceType: 'Price type',
  recurringChargePeriodType: 'Charge period', phoneNumber: 'Phone number',
  otherOperator: 'Other operator', clearinghouse: 'Clearinghouse',
  scheduledCutover: 'Cutover', createdAt: 'When', useCase: 'Use case',
  customizationId: 'Customization', profileId: 'Profile', paymentReference: 'Payment ref',
  buyerStatus: 'Buyer says', lastError: 'Last error', sentAt: 'Sent', batchRef: 'Batch',
  receivedAt: 'Received',
  // Visitor-consent ledger (was "Insight"): human headers for the raw fields.
  visitorId: 'Visitor', partyId: 'Customer', analyticsConsent: 'Analytics consent',
  personalizationConsent: 'Personalization consent', utmSource: 'Campaign source',
};
const EVENT_LABELS = Object.fromEntries(TRIGGER_EVENTS.map((t) => [t.value, t.label]));

const el = (id) => document.getElementById(id);

// Cached party-name lookup — orders carry only a party id, but a back-office
// agent needs the human's name. TMF632 individual → "Given Family".
const PARTY_BASE = '/tmf-api/party/v4';
const _partyNames = new Map();
async function partyName(id) {
  if (!id) return '—';
  if (_partyNames.has(id)) return _partyNames.get(id);
  let name = id.slice(0, 8) + '…';
  try {
    const r = await authFetch(`${PARTY_BASE}/individual/${id}`);
    if (r.ok) {
      const p = await r.json();
      name = [p.givenName, p.familyName].filter(Boolean).join(' ')
        || p.fullName || p.name || name;
    } else {
      const o = await authFetch(`${PARTY_BASE}/organization/${id}`);
      if (o.ok) { const p = await o.json(); name = p.tradingName || p.name || name; }
    }
  } catch { /* keep the id stub */ }
  _partyNames.set(id, name);
  return name;
}
