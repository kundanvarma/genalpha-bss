/*
 * One search field, typed results — CSR-UX-003 (#147).
 *
 * The desk has ONE box. What comes back says WHAT each hit is before it says
 * anything else, because "open this" is only safe when the agent knows what
 * they are about to open. Two kinds of query, both typed:
 *
 *   a REFERENCE — a customer, order, ticket, subscription or device-agreement
 *   id, or a phone number the caller reads out — is probed against every
 *   object type in parallel, and every hit becomes its own typed row PLUS a
 *   row for the customer behind it. The agent picks the object, not a guess;
 *
 *   FREE TEXT — a name or an email — searches customers, which is the only
 *   text search the components offer today (no `q` on orders, tickets or
 *   offerings; see the arc doc's Honest limits).
 *
 * Natural-language search, when it comes, produces THESE results: a model
 * chooses the type and the reference; the rows stay exactly what they are
 * here. This file is the typed-result model that ticket builds on.
 *
 * Destinations are the object's own place on the customer's page — the areas
 * Customer360 already routes by hash — never a list the agent must search
 * again.
 */
import {
  deviceAgreementById, getCustomer, getTicket, holdingCounts,
  numberOwner, orderById, productById, searchCustomers, activeServicesOf,
} from '../../api.js';
import {
  customerResult, deviceResult, numberOfService, orderResult, ownerOf,
  serviceResult, subscriptionResult, ticketResult,
} from './resultRows.js';

/* ------------------------------------------------------------------ types -- */

/** Every result type the one box can return, in the order the groups read.
 *  `label` is what the agent calls it; `plural` heads the group. */
export const TYPES = [
  ['customer', { label: 'Customer', plural: 'Customers' }],
  ['subscription', { label: 'Subscription', plural: 'Subscriptions' }],
  ['order', { label: 'Order', plural: 'Orders' }],
  ['ticket', { label: 'Ticket', plural: 'Tickets' }],
  ['device', { label: 'Device', plural: 'Devices' }],
];
const TYPE_LABEL = Object.fromEntries(TYPES.map(([k, v]) => [k, v.label]));
export const typeLabel = (t) => TYPE_LABEL[t] || t;

/** Group typed results for rendering: [{type, plural, results}], empty groups dropped. */
export function grouped(results) {
  return TYPES
    .map(([type, meta]) => ({ type, plural: meta.plural, results: results.filter((r) => r.type === type) }))
    .filter((g) => g.results.length);
}

/* -------------------------------------------------------------- the query -- */

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const HEX32 = /^[0-9a-f]{32}$/i;
const PHONE = /^\+?[\d\s-]{6,}$/;

export const looksLikeReference = (s) => UUID.test(s.trim()) || HEX32.test(s.trim());
export const looksLikeNumber = (s) => PHONE.test(s.trim());

/** What the box thinks it was handed — shown to the agent, so a query that
 *  found nothing says WHY it found nothing instead of shrugging. */
export function queryKind(s) {
  if (!s.trim()) return 'empty';
  if (looksLikeReference(s)) return 'reference';
  if (looksLikeNumber(s)) return 'number';
  return 'text';
}

/* ------------------------------------------------------------- resolution -- */

/* One box asks five components at once, so one slow component must not freeze
 * the desk: every probe gets a deadline and contributes nothing if it misses
 * it. A miss costs the agent a row; a hang costs them the search. Generous on
 * purpose — the deadline exists to bound a HANG, and a tighter one silently
 * dropped the owner's row on a loaded laptop, which is the worse failure. */
const PROBE_MS = 15000;
const hit = (p) => Promise.race([
  Promise.resolve(p).then((v) => v).catch(() => null),
  new Promise((resolve) => { setTimeout(() => resolve(null), PROBE_MS); }),
]);

/** Every object type, probed at once. A reference belongs to one of them. */
async function byReference(ref) {
  const [customer, order, ticket, product, device] = await Promise.all([
    hit(getCustomer(ref)), hit(orderById(ref)), hit(getTicket(ref)),
    hit(productById(ref)), hit(deviceAgreementById(ref)),
  ]);
  const MATCHED = 'matched the reference you pasted';
  const results = [];
  if (customer) results.push(customerResult(customer, MATCHED));
  if (order) results.push(orderResult(order, MATCHED));
  if (ticket) results.push(ticketResult(ticket, MATCHED));
  if (product) results.push(subscriptionResult(product, MATCHED));
  if (device) results.push(deviceResult(device, MATCHED));

  // whoever owns what we found gets a row of their own, fetched once
  const owners = [...new Set([order, ticket, product, device].filter(Boolean).map(ownerOf).filter(Boolean))]
    .filter((id) => id !== customer?.id);
  const people = await Promise.all(owners.map((id) => hit(getCustomer(id))));
  for (const p of people.filter(Boolean)) results.push(customerResult(p, 'owns the object that reference names'));
  return results;
}

/** A phone number resolves to the line it runs on AND the person who holds it. */
async function byNumber(raw) {
  const owner = await hit(numberOwner(raw));
  if (!owner?.partyId) return [];
  const [customer, services] = await Promise.all([
    hit(getCustomer(owner.partyId)), hit(activeServicesOf(owner.partyId)),
  ]);
  const bare = (s) => String(s || '').replace(/[\s-]/g, '').replace(/^\+/, '');
  const line = (services || []).find((sv) => bare(numberOfService(sv)) === bare(owner.number));
  return [
    ...(line ? [serviceResult(line, owner.number, owner.partyId)] : []),
    ...(customer ? [customerResult(customer, `holds the number ${owner.number}`)] : []),
  ];
}

/** Name or email. Customers are the one text search the components offer. */
async function byText(text) {
  const people = await searchCustomers(text);
  return (people || []).map((c) => customerResult(c, null));
}

/** The one box. An empty box still lists the desk's customers, as it always did. */
export async function resolveQuery(q) {
  const kind = queryKind(q);
  if (kind === 'empty') return byText('');
  if (kind === 'reference') return byReference(q.trim());
  if (kind === 'number') return byNumber(q.trim());
  return byText(q.trim());
}

/**
 * Identification context that costs a round trip: how many subscriptions the
 * customer actually holds, and how many are stopped. Only for the rows the
 * agent can see (`take`), and fail-soft — a missing count must never blank a
 * row that already identifies somebody.
 */
export async function withHoldings(results, take = 8) {
  const people = results.filter((r) => r.type === 'customer').slice(0, take);
  const counts = await Promise.all(people.map((r) => hit(holdingCounts(r.id))));
  const byId = new Map(people.map((r, i) => [r.id, counts[i]]));
  return results.map((r) => {
    const c = r.type === 'customer' ? byId.get(r.id) : undefined;
    if (!c) return r;
    const active = c.byStatus?.active || 0;
    const stopped = (c.total || 0) - active;
    return {
      ...r,
      holding: active === 0 && stopped === 0 ? 'no subscriptions yet'
        : `${active} active subscription${active === 1 ? '' : 's'}${stopped ? ` · ${stopped} not active` : ''}`,
    };
  });
}
