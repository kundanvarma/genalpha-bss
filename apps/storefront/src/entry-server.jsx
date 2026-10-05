/*
 * THE SERVER ENTRY (#180).
 *
 * The same App, the same router, rendered to a string instead of into a DOM
 * node. Nothing here is a server-only copy of a page: if this diverges from
 * `main.jsx` then the server and the browser render different documents, which
 * is the defect dual-rendering by User-Agent already has and the reason this
 * work exists.
 *
 * REQUEST SCOPE. One process serves every tenant, so "which tenant is this?"
 * cannot be a module variable. It is request-scoped storage, which is correct
 * by construction rather than correct by the accident that `renderToString`
 * happens not to await: the moment this moves to a streaming renderer — which
 * it should, for Suspense and time-to-first-byte — module state would start
 * serving one operator's prices under another's brand, silently and only under
 * load. `async_hooks` lives here and nowhere else, so the browser bundle never
 * sees it.
 */
import React from 'react';
import { AsyncLocalStorage } from 'node:async_hooks';
import { renderToString } from 'react-dom/server';
import { StaticRouter } from 'react-router';
import App from './App.jsx';
import { setConfigResolver } from './config.js';
import { setDataResolver } from './ssr-data.js';
import { LINES } from './pages/lines.jsx';

const request = new AsyncLocalStorage();

/**
 * WHICH CATEGORY URLS EXIST, exported from the same list the pages render from.
 *
 * The runtime has to answer 404 for a shelf that is not a shelf, and it must not
 * do that from a second copy of the list: nginx keeps one and the catalog's
 * sitemap keeps another, and three copies of a closed list is three chances for
 * a URL to be a page on one surface and a 404 on the next. This is the list the
 * router actually routes, so the server cannot disagree with the app it serves.
 * The suite pins all three together.
 */
export const SHELVES = LINES.map((l) => l.slug);

setConfigResolver(() => request.getStore()?.config ?? null);
setDataResolver(() => request.getStore()?.data ?? null);

/**
 * @param {string} path  the app's own path, with /shop already stripped
 * @param {object} config the tenant manifest the gateway would stamp into the page
 * @param {object} data  what the server resolved for this page, or null
 */
export function render(path, config, data) {
  // The gateway strips /shop before this service sees the request, but the
  // router keeps the basename so every link it renders carries the prefix a
  // browser will actually follow. So the location is put back.
  const location = path.startsWith('/shop') ? path : '/shop' + (path === '/' ? '' : path);
  return request.run({ config: config || {}, data: data || null }, () => renderToString(
    <StaticRouter location={location} basename="/shop">
      <App />
    </StaticRouter>,
  ));
}

/** Test seam: run something inside a request scope without rendering. */
export function withRequest(config, data, fn) {
  return request.run({ config: config || {}, data: data || null }, fn);
}
