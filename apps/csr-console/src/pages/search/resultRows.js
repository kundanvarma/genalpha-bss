/*
 * The shape of one search result — CSR-UX-002 (#146).
 *
 * A row is an IDENTIFICATION surface: what the object is, what it is called in
 * operator words, the two or three facts that tell it apart from its neighbour,
 * the statuses an agent must not miss, and where it opens. Deliberately not a
 * mini dashboard: the review's constraint is that results stay identification
 * and fast selection, so nothing here costs a round trip except the holding
 * count, which typedSearch.js adds afterwards.
 *
 * Every builder returns the same record, so one row component renders them all:
 *   { type, id, to, title, facts: [{label, value, title?, mono?}],
 *     chips: [{text, tone}], why }
 */
/* A reference an agent can read out loud. A UUID shortens to its first eight
 * hex characters; anything else (a seeded key, an external agreement number)
 * is shown as it is, because truncating `device-party-7` to `devicepa` turns a
 * reference into noise. */
const shortRef = (id) => {
  const s = String(id || '');
  return /^[0-9a-f]{8}-?[0-9a-f-]{23,27}$/i.test(s) ? s.replace(/-/g, '').slice(0, 8)
    : (s.length > 20 ? `${s.slice(0, 20)}…` : s);
};
const nameOf = (c) => [c.givenName, c.familyName].filter(Boolean).join(' ').trim() || 'Unnamed customer';
const medium = (c, type) => (c.contactMedium || [])
  .filter((m) => m.mediumType === type && m.characteristic?.source !== 'folkeregisteret');
const emailOf = (c) => medium(c, 'email')[0]?.characteristic?.emailAddress || null;
const phonesOf = (c) => medium(c, 'phone').map((m) => m.characteristic?.phoneNumber).filter(Boolean);
const cityOf = (c) => medium(c, 'postalAddress')[0]?.characteristic?.city || null;
const registered = (c) => (c.contactMedium || []).some((m) => m.characteristic?.source === 'folkeregisteret');

/** The number a service runs on, as the service inventory reports it. */
export const numberOfService = (sv) => (sv.supportingResource || []).map((r) => r.value).find(Boolean) || null;

/** The customer a TMF object belongs to (`customer` role first, else the first party). */
export const ownerOf = (obj) => (obj?.relatedParty || []).find((p) => p.role === 'customer')?.id
  || (obj?.relatedParty || [])[0]?.id || null;

/** A customer in operator words: person or business, and the household role
 *  when there is one — the fact that tells two same-named people apart. */
function partyKind(c) {
  const kind = c['@type'] === 'Organization' ? 'Business' : 'Person';
  const role = c.householdPayer?.role;
  if (role === 'payer') return `${kind} · household payer`;
  if (role) return `${kind} · household ${role}`;
  return kind;
}

/**
 * A customer result: an identification surface, never a dashboard (CSR-UX-002).
 * Line one is the name. Line two is what tells two of them apart — the
 * reference, the type, the email, the phone, the town. Line three is the
 * holding count, filled in by `withHoldings` once it arrives. The chips on the
 * right are the statuses an agent must not miss.
 */
export function customerResult(c, why) {
  return {
    type: 'customer',
    id: c.id,
    to: `/customer/${c.id}`,
    title: nameOf(c),
    facts: [
      { label: 'Customer ref', value: shortRef(c.id), title: c.id, mono: true },
      { label: 'Type', value: partyKind(c) },
      ...(emailOf(c) ? [{ label: 'Email', value: emailOf(c) }] : []),
      ...phonesOf(c).slice(0, 2).map((p) => ({ label: 'Phone', value: p })),
      ...(cityOf(c) ? [{ label: 'Town', value: cityOf(c) }] : []),
    ],
    chips: [
      ...(c.deceased === true ? [{ text: 'deceased', tone: 'cancelled' }] : []),
      ...(c.addressProtected === true ? [{ text: 'protected address', tone: 'cancelled' }] : []),
      ...(registered(c) ? [{ text: '✓ registered', tone: 'active' }] : []),
    ],
    why,
  };
}

const money = (a, cur) => (a === undefined || a === null ? null : `${a} ${cur || ''}`.trim());

export function subscriptionResult(product, why, ownerId) {
  const owner = ownerId || ownerOf(product);
  return {
    type: 'subscription',
    id: product.id,
    to: owner ? `/customer/${owner}#services` : null,
    title: product.name || product.productOffering?.name || 'Subscription',
    facts: [
      { label: 'Subscription ref', value: shortRef(product.id), title: product.id, mono: true },
      ...(product.startDate ? [{ label: 'Since', value: String(product.startDate).slice(0, 10) }] : []),
    ],
    chips: product.status ? [{ text: product.status, tone: product.status }] : [],
    why,
  };
}

/** A running line, found by the number the caller read out. */
export function serviceResult(sv, number, ownerId) {
  return {
    type: 'subscription',
    id: sv.id,
    to: ownerId ? `/customer/${ownerId}#services` : null,
    title: sv.name || 'Service',
    facts: [
      { label: 'Number', value: number, mono: true },
      ...(sv.category ? [{ label: 'Kind', value: sv.category }] : []),
    ],
    chips: sv.state ? [{ text: sv.state, tone: sv.state }] : [],
    why: `the number ${number} runs on this line`,
  };
}

const orderTitle = (o) => {
  const names = (o.productOrderItem || [])
    .map((i) => i.productOffering?.name).filter(Boolean);
  if (names.length) return names.slice(0, 2).join(' + ') + (names.length > 2 ? ` +${names.length - 2} more` : '');
  return o.description || 'Order';
};

export function orderResult(o, why, ownerId) {
  const owner = ownerId || ownerOf(o);
  return {
    type: 'order',
    id: o.id,
    to: owner ? `/customer/${owner}#activity` : null,
    title: orderTitle(o),
    facts: [
      { label: 'Order ref', value: shortRef(o.id), title: o.id, mono: true },
      ...(o.orderDate ? [{ label: 'Ordered', value: String(o.orderDate).slice(0, 10) }] : []),
    ],
    chips: o.state ? [{ text: o.state, tone: o.state }] : [],
    why,
  };
}

export function ticketResult(t, why, ownerId) {
  const owner = ownerId || ownerOf(t);
  return {
    type: 'ticket',
    id: t.id,
    to: owner ? `/customer/${owner}#activity` : null,
    title: t.name || t.description || 'Ticket',
    facts: [
      { label: 'Ticket ref', value: shortRef(t.id), title: t.id, mono: true },
      ...(t.severity ? [{ label: 'Severity', value: t.severity }] : []),
      ...(t.creationDate ? [{ label: 'Raised', value: String(t.creationDate).slice(0, 10) }] : []),
    ],
    chips: t.status ? [{ text: t.status, tone: t.status }] : [],
    why,
  };
}

export function deviceResult(a, why) {
  return {
    type: 'device',
    id: a.id,
    // the Devices desk, focused on this one agreement — never the whole list
    to: `/devices?agreement=${encodeURIComponent(a.id)}`,
    // the same name the device desk gives it, so the two screens agree
    title: a.device?.name || a.device?.model || a.device?.id || 'Financed device',
    facts: [
      { label: 'Agreement ref', value: shortRef(a.id), title: a.id, mono: true },
      ...(a.device?.imei ? [{ label: 'IMEI', value: a.device.imei, mono: true }] : []),
      ...(money(a.monthlyAmount, a.currency) ? [{ label: 'Per month', value: money(a.monthlyAmount, a.currency) }] : []),
      // no owner key here: when the customer can be resolved they get a row of
      // their own, under their NAME, which is what the desk speaks
    ],
    chips: a.status ? [{ text: a.status, tone: a.status }] : [],
    why,
  };
}
