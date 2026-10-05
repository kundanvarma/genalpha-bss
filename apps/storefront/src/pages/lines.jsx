import { Link } from 'react-router-dom';
import { t } from '../i18n.js';

/*
 * THE LINES OF BUSINESS, AND THEIR URLS (#180).
 *
 * The shop groups offers by line of business, and that grouping used to live
 * entirely in component state behind `?tab=`. A person could use it; a crawler
 * could not follow it, and an answer engine had no page to cite for "mobile
 * plans" at all.
 *
 * The rules live here rather than in either page, so a shelf cannot mean one
 * thing on the tab and another on its own page — and so neither page has to
 * import the other, which was a cycle waiting to happen.
 */
export const LINES = [
  { slug: 'bundles', label: 'Bundles', bundlesOnly: true },
  { slug: 'mobile', label: 'Mobile', categories: ['Mobile plans'] },
  { slug: 'internet', label: 'Internet', categories: ['Broadband'] },
  { slug: 'tv', label: 'TV & Streaming', categories: ['TV & Add-ons', 'Partner services'] },
  { slug: 'devices', label: 'Devices', categories: ['Devices', 'Equipment'] },
  { slug: 'security', label: 'Security', categories: ['Security', 'Insurance'] },
  { slug: 'top-ups', label: 'Top-ups', categories: ['Top-ups'] },
];

const categoryOf = (o) => ((o.category || [])[0] || {}).name || '';

/** The offerings on one shelf, by the same rule the shop tab uses. */
export function itemsOn(line, offerings) {
  const all = Array.isArray(offerings) ? offerings : [];
  if (!line) return [];
  return line.bundlesOnly
    ? all.filter((o) => o.isBundle)
    : all.filter((o) => !o.isBundle && (line.categories || []).includes(categoryOf(o)));
}

/** Every shelf as a real link — the crawl graph, and a shared URL for a person. */
export function ShelfLinks({ except, testid = 'shelf-links' }) {
  return (
    <nav className="dim small" data-testid={testid} style={{ margin: '0 0 .75rem' }}>
      {LINES.filter((l) => l.slug !== except).map((l, i) => (
        <span key={l.slug}>{i > 0 && ' · '}<Link to={`/category/${l.slug}`}>{t(l.label)}</Link></span>
      ))}
    </nav>
  );
}
