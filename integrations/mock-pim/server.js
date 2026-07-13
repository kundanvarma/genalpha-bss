/*
 * Mock external PIM — stands in for the product-information system an
 * operator already owns (Akeneo, inRiver, a homegrown DAM). It knows nothing
 * about this BSS: products are keyed by NAME, media come back as roles and
 * hrefs, and the imagery itself is generated device art served from /assets.
 *
 * The catalog's ExternalPimContentSource calls:
 *   GET /media?product=<name>   -> { product, media: [{role, type, href}] }
 * 404 means "not content-managed here" — the catalog keeps its own art.
 *
 * Hrefs are gateway-relative (/pim/assets/...) so the same URL works in a
 * browser through the gateway route.
 */
'use strict';

const http = require('http');
const { URL } = require('url');

const PORT = process.env.PORT || 8080;

/* The PIM's product database: device content an operator's merchandising
 * team would curate. Slugs key the generated art. */
const PRODUCTS = {
  'nordic phone x': {
    slug: 'nordic-phone-x', body: '#2f4550', screen: '#0b132b', accent: '#8fd0d0',
    colors: { 'Fjord Blue': '#3a6ea5', 'Midnight Black': '#1c2321', 'Glacier White': '#dfe7ea' },
  },
  'samsung galaxy s26': {
    slug: 'galaxy-s26', body: '#22303c', screen: '#101820', accent: '#7fd1ae',
    colors: { 'Icy Blue': '#9bc4e2', 'Phantom Black': '#1c2321' },
  },
  'apple iphone 17': {
    slug: 'iphone-17', body: '#3c3744', screen: '#14121f', accent: '#f2c14e',
    colors: { 'Starlight': '#e8e1d5', 'Deep Purple': '#4b3b60' },
  },
};

function phoneSvg(p, view, tint) {
  const body = tint || p.body;
  const views = {
    front: `<rect x="60" y="30" width="120" height="240" rx="22" fill="${body}"/>
      <rect x="70" y="52" width="100" height="196" rx="8" fill="${p.screen}"/>
      <circle cx="120" cy="42" r="4" fill="${p.screen}"/>
      <rect x="82" y="70" width="76" height="8" rx="4" fill="${p.accent}" opacity="0.9"/>
      <rect x="82" y="90" width="52" height="6" rx="3" fill="#ffffff" opacity="0.35"/>
      <circle cx="120" cy="230" r="12" fill="none" stroke="${p.accent}" stroke-width="3"/>`,
    back: `<rect x="60" y="30" width="120" height="240" rx="22" fill="${body}"/>
      <rect x="74" y="46" width="40" height="72" rx="14" fill="${p.screen}"/>
      <circle cx="94" cy="66" r="10" fill="#0a0a0a" stroke="${p.accent}" stroke-width="2"/>
      <circle cx="94" cy="96" r="10" fill="#0a0a0a" stroke="${p.accent}" stroke-width="2"/>
      <circle cx="150" cy="60" r="4" fill="${p.accent}"/>`,
    side: `<rect x="106" y="30" width="28" height="240" rx="12" fill="${body}"/>
      <rect x="134" y="80" width="4" height="30" rx="2" fill="${p.accent}"/>
      <rect x="134" y="120" width="4" height="18" rx="2" fill="${p.accent}"/>`,
  };
  return `<svg xmlns="http://www.w3.org/2000/svg" width="240" height="300" viewBox="0 0 240 300">
<defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
<stop offset="0" stop-color="#f4f7f7"/><stop offset="1" stop-color="#dde7e7"/></linearGradient></defs>
<rect width="240" height="300" rx="18" fill="url(#bg)"/>
${views[view] || views.front}
</svg>`;
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');

  if (url.pathname === '/media') {
    const name = (url.searchParams.get('product') || '').trim().toLowerCase();
    const p = PRODUCTS[name];
    if (!p) {
      res.writeHead(404, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ error: 'product not content-managed here' }));
    }
    const media = ['front', 'back', 'side'].map((view) => ({
      role: `gallery-${view}`, type: 'image/svg+xml',
      href: `/pim/assets/${p.slug}-${view}.svg`,
    }));
    for (const color of Object.keys(p.colors)) {
      media.push({
        role: `variant-${color}`, type: 'image/svg+xml',
        href: `/pim/assets/${p.slug}-variant-${encodeURIComponent(color)}.svg`,
      });
    }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ product: name, media }));
  }

  const asset = url.pathname.match(/^\/assets\/(.+)\.svg$/);
  if (asset) {
    const file = decodeURIComponent(asset[1]);
    for (const p of Object.values(PRODUCTS)) {
      if (!file.startsWith(p.slug)) continue;
      const rest = file.slice(p.slug.length + 1);
      let svg = null;
      if (rest.startsWith('variant-')) {
        const color = rest.slice('variant-'.length);
        if (p.colors[color]) svg = phoneSvg(p, 'front', p.colors[color]);
      } else if (['front', 'back', 'side'].includes(rest)) {
        svg = phoneSvg(p, rest);
      }
      if (svg) {
        res.writeHead(200, { 'Content-Type': 'image/svg+xml', 'Cache-Control': 'public, max-age=86400' });
        return res.end(svg);
      }
    }
    res.writeHead(404);
    return res.end();
  }

  if (url.pathname === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end('{"status":"UP"}');
  }

  res.writeHead(404);
  res.end();
});

server.listen(PORT, () => console.log(`mock-pim listening on ${PORT}`));
