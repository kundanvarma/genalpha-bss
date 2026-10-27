// What the cockpit's rows and order cards are made of: pure shaping, no state.
// Moved out of Customer360.jsx (#358) so the page shrinks as panels join it.
import { numberOf } from './ServiceRows.jsx';

// A bundle order decomposes into product families — the agent sees the same
// component tree the customer does, so "why is it in progress" answers itself.
export const FAMILY_ICON = { internet: '🌐', tv: '📺', mobile: '📱', device: '📦', other: '•' };
export function orderLeaves(items, into = []) {
  for (const it of items || []) {
    const kids = it.productOrderItem || [];
    if (kids.length) orderLeaves(kids, into);
    else if (it.productOffering) into.push(it);
  }
  return into;
}
export function familyOf(item) {
  if (item.componentType) return item.componentType;
  const n = (item.productOffering?.name || '').toLowerCase();
  if (/fiber|fibre|broadband|internet|dsl/.test(n)) return 'internet';
  if (/\btv\b|stream|sports|entertain|kids tv/.test(n)) return 'tv';
  if (/mobile|sim|5g|data|gb\b/.test(n)) return 'mobile';
  if (/iphone|galaxy|pixel|phone|handset|watch/.test(n)) return 'device';
  return 'other';
}
export const leafGlyph = (st) => st === 'completed' ? '✓' : st === 'cancelled' ? '✗' : '⏳';

/** Products are what the customer bought; services are what runs. One row per product, its service beneath it, orphans after. */
export function mergeProductsAndServices(products, services) {
  const free = [...services];
  const rows = [];
  const seen = new Map(); // name -> row, so twenty identical top-ups read as one row ×20
  for (const p of products) {
    // the product says which service realises it (TMF637 realizingService); the name match is only the fallback
    const realized = new Set((p.realizingService || []).map((r) => r.id).filter(Boolean));
    let i = realized.size ? free.findIndex((sv) => realized.has(sv.id)) : -1;
    if (i < 0) i = free.findIndex((sv) => (sv.name || '').toLowerCase() === (p.name || '').toLowerCase());
    const sv = i >= 0 ? free.splice(i, 1)[0] : null;
    const key = p.name + '|' + (sv ? numberOf(sv) || sv.id : '') + '|' + p.status;
    if (!sv && seen.has(key)) { seen.get(key).count++; continue; }
    const row = { product: p, service: sv, count: 1 };
    rows.push(row);
    if (!sv) seen.set(key, row);
  }
  for (const sv of free) rows.push({ product: null, service: sv, count: 1 });
  return rows;
}
