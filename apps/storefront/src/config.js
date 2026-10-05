/*
 * THE TENANT'S OWN WORDS, reachable from a server as well as a browser.
 *
 * The gateway stamps a per-tenant config object into the page as
 * `window.BSS_STOREFRONT_CONFIG` — issuer, brand, locale, currency, support
 * numbers. Two modules used to read it AT IMPORT TIME (`auth.js`,
 * `address.js`), which is harmless in a browser and fatal on a server: `window`
 * does not exist there, so the module graph threw before anything could render.
 *
 * On a server one process serves every tenant, so the answer has to be scoped
 * to the request rather than to the module. This file holds only the question;
 * the server runtime installs the resolver that answers it, which keeps Node's
 * `async_hooks` out of the browser bundle entirely.
 */

let resolver = null;

/**
 * Server only: how to find the config for the request being rendered.
 * Installed once by the server entry, which backs it with request-scoped
 * storage. Absent in the browser, where the global is the answer.
 */
export function setConfigResolver(fn) {
  resolver = fn;
}

/** The tenant's configuration, wherever this is running. Never null. */
export function config() {
  if (resolver) {
    const scoped = resolver();
    if (scoped) {
      return scoped;
    }
  }
  if (typeof window !== 'undefined' && window.BSS_STOREFRONT_CONFIG) {
    return window.BSS_STOREFRONT_CONFIG;
  }
  return {};
}
