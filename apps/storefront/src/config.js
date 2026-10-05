/*
 * THE TENANT'S OWN WORDS, reachable from a server as well as a browser.
 *
 * The gateway stamps a per-tenant config object into the page as
 * `window.BSS_STOREFRONT_CONFIG` — issuer, brand, locale, currency, support
 * numbers. Two modules used to read it AT IMPORT TIME (`auth.js`,
 * `address.js`), which is harmless in a browser and fatal on a server: `window`
 * does not exist there, so the module graph threw before anything could render.
 * That was the first thing in the way of server-side rendering (#180).
 *
 * So it is a function, not a captured constant. On the client it reads the
 * global the gateway stamped. On the server the renderer sets it per request,
 * because one process serves every tenant and a value captured at import would
 * be whichever tenant happened to be first.
 */

let injected = null;

/** The tenant's configuration, wherever this is running. Never null. */
export function config() {
  if (injected) {
    return injected;
  }
  if (typeof window !== 'undefined' && window.BSS_STOREFRONT_CONFIG) {
    return window.BSS_STOREFRONT_CONFIG;
  }
  return {};
}

/**
 * Server only: the config for the request about to be rendered.
 *
 * <p>TRACER LIMIT: this is module state, so it is correct for one render at a
 * time and wrong under concurrency. The renderer sets it immediately before a
 * synchronous `renderToString` and clears it after, which holds today because
 * that render does not await. A multi-tenant SSR process serving concurrent
 * requests needs this moved into request-scoped storage (AsyncLocalStorage),
 * and that is named in the SSR arc rather than left to be discovered.</p>
 */
export function setConfig(next) {
  injected = next || null;
}
