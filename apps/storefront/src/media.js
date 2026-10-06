/*
 * A URL THAT IS SAFE TO PUT IN AN <img src>.
 *
 * Product imagery reaches this channel from three places that are not the same
 * level of trusted: the catalogue's own attachments, an operator's external PIM
 * (a vendor seam — somebody else's server), and the server-rendered seed, which
 * the client reads out of the DOM. CodeQL named the last one first, as "DOM text
 * reinterpreted as HTML", because reading a URL out of the document and putting
 * it straight into an attribute is a shape worth refusing on principle.
 *
 * The honest reading is that all three carry the same risk, and the one the
 * scanner noticed is not the dangerous one: the seed is written by our own
 * renderer. So this guards the SINK rather than the source — every image URL
 * this channel renders passes through here, whoever supplied it.
 *
 * What survives: http, https, and our own root-relative paths. What does not:
 * `javascript:`, `data:` (an SVG payload is still a payload), protocol-relative
 * `//host` (it inherits the page's scheme and hides its origin), and anything
 * `new URL` cannot parse. A rejected URL renders no image, which is the right
 * failure: a missing picture, never an executed one.
 */
export function imageUrl(raw) {
  if (typeof raw !== 'string') {
    return null;
  }
  const url = raw.trim();
  if (!url || url.startsWith('//')) {
    return null;
  }
  if (url.startsWith('/')) {
    return url;                 // ours, served through the gateway
  }
  try {
    const { protocol } = new URL(url, 'https://relative.invalid');
    return protocol === 'http:' || protocol === 'https:' ? url : null;
  } catch {
    return null;                // not a URL at all
  }
}
