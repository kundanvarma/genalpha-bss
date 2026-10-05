/*
 * WHAT THE SERVER ALREADY KNEW.
 *
 * Public pages fetch in `useEffect`, which never runs during `renderToString`.
 * A server render without this produces a complete page showing a loading
 * state — valid HTML that says nothing, which is exactly as useless to a
 * crawler as the empty shell it replaces.
 *
 * So the renderer fetches first and leaves the result here. A page reads it for
 * its FIRST state and otherwise behaves exactly as it did: if nothing was
 * seeded, the effect fetches as before, so the client-only path is unchanged.
 *
 * The same payload is embedded in the document as `window.__SSR_DATA__`, so the
 * browser hydrates from what the server already rendered rather than fetching
 * it again and flashing.
 */

let seeded = null;

/** What the server resolved for this page, or null when nothing was seeded. */
export function initialData() {
  if (seeded) {
    return seeded;
  }
  if (typeof window !== 'undefined' && window.__SSR_DATA__) {
    return window.__SSR_DATA__;
  }
  return null;
}

/**
 * Server only. Same request-scoping caveat as `config()`: this is module state,
 * correct for one synchronous render at a time, and it must move into
 * request-scoped storage before the renderer serves concurrent requests.
 */
export function setInitialData(next) {
  seeded = next || null;
}
