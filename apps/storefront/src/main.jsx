import React from 'react';
import { createRoot, hydrateRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App.jsx';
import { serverRendered } from './ssr-data.js';
import './styles.css';

// White-labeling is more than a logo: the host tenant's brand color themes
// the whole channel (genalpha's teal is the stylesheet default; nova goes purple).
const brand = window.BSS_STOREFRONT_CONFIG || {};

// A tenant's brand color is chosen for identity, not contrast — as small text
// on the soft tint it can dip below the WCAG AA 4.5:1 line. Derive a readable
// shade (darker on light backgrounds, lighter on dark) so ANY brand stays
// legible, and theme small-text uses off --teal-text instead of raw --teal.
function readableText(hex, bgHex) {
  const rgb = (h) => { const n = parseInt(h.replace('#', ''), 16); return [n >> 16 & 255, n >> 8 & 255, n & 255]; };
  const lin = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
  const lum = ([r, g, b]) => 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
  const ratio = (a, b) => { const la = lum(a), lb = lum(b); return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05); };
  const bg = rgb(bgHex);
  const darken = lum(bg) > 0.5; // light bg → darken text; dark bg → lighten it
  let c = rgb(hex);
  for (let i = 0; i < 48 && ratio(c, bg) < 5.5; i++) { // 5.5 vs paper clears 4.5 on the tint
    c = c.map((v) => (darken ? Math.max(0, v - 6) : Math.min(255, v + 6)));
  }
  return '#' + c.map((v) => v.toString(16).padStart(2, '0')).join('');
}

if (brand.brandColor) {
  document.documentElement.style.setProperty('--teal', brand.brandColor);
  document.documentElement.style.setProperty('--teal-soft', brand.brandColor + '1F');
  const dark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
  document.documentElement.style.setProperty('--teal-text', readableText(brand.brandColor, dark ? '#0E181C' : '#FAFBFA'));
}
// The TITLE IS THE SERVER'S when the server set one. It writes a real
// per-page title (the product's name, the shelf's) into the document head from
// the catalogue's own projection; overwriting it here with a generic one undoes
// that for the browser tab and for anything that reads the rendered DOM.
// A client-rendered shell has no such title, so there it still gets one.
if (brand.brandName && !serverRendered()) document.title = `${brand.brandName} · shop`;

/*
 * HYDRATE WHAT THE SERVER SENT, when it sent anything (#180).
 *
 * `createRoot().render()` on a server-rendered document THROWS THE MARKUP AWAY
 * and rebuilds it — the visitor sees the page, then sees it replaced, and every
 * byte the renderer produced is wasted. That was harmless while only crawlers
 * were served (they never run this file). It is the whole point once humans
 * are, so a document that arrived with content is hydrated instead.
 *
 * The seed is the signal, not the markup: the `ssr-data` JSON block is written
 * only by the renderer, so an nginx-served shell — the circuit breaker's
 * fallback, and every signed-in page — still takes the createRoot path it
 * always took.
 */
const root = document.getElementById('root');
const tree = (
  <React.StrictMode>
    <BrowserRouter basename="/shop">
      <App />
    </BrowserRouter>
  </React.StrictMode>
);
if (serverRendered() && root.hasChildNodes()) {
  hydrateRoot(root, tree);
} else {
  createRoot(root).render(tree);
}
