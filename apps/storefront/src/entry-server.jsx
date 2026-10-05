/*
 * THE SERVER ENTRY (#180 tracer).
 *
 * The same App, the same router, rendered to a string instead of into a DOM
 * node. Nothing here is a server-only copy of a page: if this diverges from
 * `main.jsx` then the server and the browser are rendering different documents,
 * which is the defect dual-rendering by User-Agent already has and the reason
 * this work exists.
 */
import React from 'react';
import { renderToString } from 'react-dom/server';
import { StaticRouter } from 'react-router';
import App from './App.jsx';
import { setConfig } from './config.js';
import { setInitialData } from './ssr-data.js';

/**
 * @param {string} path  the app's own path, with /shop already stripped
 * @param {object} config the tenant manifest the gateway would stamp into the page
 * @param {object} data  what the server resolved for this page, or null
 */
export function render(path, config, data) {
  setConfig(config);
  setInitialData(data);
  // The gateway strips /shop before this service sees the request, but the
  // router keeps the basename so every link it renders carries the prefix a
  // browser will actually follow. So the location is put back.
  const location = path.startsWith('/shop') ? path : '/shop' + (path === '/' ? '' : path);
  try {
    return renderToString(
      <StaticRouter location={location} basename="/shop">
        <App />
      </StaticRouter>,
    );
  } finally {
    // module state, cleared immediately — see the caveat in config.js
    setConfig(null);
    setInitialData(null);
  }
}
