/* A characteristic's NAME is catalogue data, and the person who authored it is
 * often a model: the product copilot names fields like an engineer
 * ("dataAmount", "validityPeriod"), and the shop printed them as they came.
 * A customer reads words. Only identifier-shaped names are rewritten —
 * camelCase, snake_case, kebab-case — so a name a product manager already wrote
 * in words ("Colour", "Screen size"), a brand ("eSIM") or an acronym is left
 * exactly as it is. */
export function plainName(name) {
  const s = String(name || '').trim();
  if (!s || /\s/.test(s) || !/[a-z][A-Z]|[_-]/.test(s)) return s;
  // a brand or an acronym is already how people say it: eSIM, iCloud, dataGB
  if (!/[_-]/.test(s) && (/^[a-z][A-Z]/.test(s) || /[A-Z]{2,}/.test(s))) return s;
  const words = s.replace(/[_-]+/g, ' ').replace(/([a-z\d])([A-Z])/g, '$1 $2').toLowerCase().trim();
  return words.charAt(0).toUpperCase() + words.slice(1);
}
