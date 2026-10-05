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
  if (typeof window !== 'undefined' && window.__SSR_DATA__) {
    return window.__SSR_DATA__;
  }
  return null;
}
