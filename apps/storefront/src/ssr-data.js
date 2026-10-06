/*
 * WHAT THE SERVER ALREADY KNEW.
 *
 * Public pages fetch in `useEffect`, which never runs during a server render. A
 * server render without this produces a complete page showing a loading state —
 * valid HTML that says nothing, which is exactly as useless to a crawler as the
 * empty shell it replaces.
 *
 * So the renderer resolves first and leaves the result here, request-scoped for
 * the same reason the config is. A page reads it for its FIRST state and
 * otherwise behaves exactly as before: nothing seeded, and the effect fetches
 * as it always did.
 *
 * The same payload is embedded in the document as `window.__SSR_DATA__`, so the
 * browser hydrates from what the server rendered rather than fetching it again
 * and flashing.
 */

import { imageUrl } from './media.js';

let resolver = null;

/** Server only — installed by the server entry, backed by request-scoped storage. */
export function setDataResolver(fn) {
  resolver = fn;
}

/** What the server resolved for this page, or null when nothing was seeded. */
export function initialData() {
  if (resolver) {
    const scoped = resolver();
    if (scoped) {
      return scoped;
    }
  }
  return fromDocument();
}

/*
 * What the server left in the document, read once.
 *
 * It arrives as a `type="application/json"` block rather than an inline
 * script because the gateway's content policy is `script-src 'self'` and
 * forbids inline script — see the note in server/ssr.mjs. A data block is not
 * executed, so the policy does not apply and nothing had to be loosened.
 */
/*
 * THE SEED IS READ OUT OF THE DOCUMENT, so its image URLs are checked on the way
 * in — the same guarantee api/catalog.js gives the fetched path, at the other
 * boundary. CodeQL named this one first ("DOM text reinterpreted as HTML"), and
 * it is right that a URL which has passed through the DOM should not reach an
 * attribute unexamined, even though our own renderer wrote it.
 */
function sanitise(seed) {
  if (!seed || typeof seed !== 'object') {
    return null;
  }
  const one = (o) => {
    if (!o || !Array.isArray(o.attachment)) {
      return o;
    }
    o.attachment = o.attachment
      .map((a) => (a && a.url ? { ...a, url: imageUrl(a.url) } : a))
      .filter((a) => !a || !('url' in a) || a.url);
    return o;
  };
  if (Array.isArray(seed.offerings)) seed.offerings = seed.offerings.map(one);
  if (seed.offering) seed.offering = one(seed.offering);
  return seed;
}

let parsed;
function fromDocument() {
  if (typeof document === 'undefined') {
    return null;
  }
  if (parsed === undefined) {
    const el = document.getElementById('ssr-data');
    try {
      parsed = el ? sanitise(JSON.parse(el.textContent)) : null;
    } catch {
      parsed = null;              // a corrupt seed is no seed; the client fetches
    }
  }
  return parsed;
}

/** Did this document arrive server-rendered? The seed is the signal. */
export function serverRendered() {
  return fromDocument() != null;
}
