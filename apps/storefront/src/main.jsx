import React from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App.jsx';
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
if (brand.brandName) document.title = `${brand.brandName} · shop`;

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <BrowserRouter basename="/shop">
      <App />
    </BrowserRouter>
  </React.StrictMode>,
);
