/* What Home reads off a service or an order: its kind, its number, its place,
 * an order's leaf items, a short date. Pure — no fetching, no state. */
export const kindOf = (sv) => {
  const c = String(sv.category || sv.serviceType || '').toLowerCase();
  const n = String(sv.name || '').toLowerCase();
  if (/mobile|sim/.test(c) || /mobile|sim\b|5g|gb\b/.test(n)) return 'mobile';
  if (/broadband|fib|dsl|internet/.test(c) || /fiber|fibre|broadband|dsl|internet/.test(n)) return 'broadband';
  if (/\btv\b|iptv|stream/.test(c) || /\btv\b|stream/.test(n)) return 'tv';
  return 'other';
};
export const numberOf = (sv) => (sv.supportingResource || []).map((r) => r.value).find(Boolean) || null;
export const placeOf = (sv) => { const p = (sv.place || [])[0]; return p ? (p.name || [p.streetName || p.street1, p.city].filter(Boolean).join(', ')) : null; };
export const leaves = (items, into = []) => { for (const it of items || []) { const kids = it.productOrderItem || []; if (kids.length) leaves(kids, into); else if (it.productOffering) into.push(it); } return into; };
export const dt = (v) => v ? new Date(v).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '';
