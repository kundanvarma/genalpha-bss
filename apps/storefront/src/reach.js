/* EMPTY IS NOT UNAVAILABLE (#289) — the shop's half. Home reads sixteen things
 * at once and must render on a bad day, so each read is fail-soft — but a read
 * that failed into [] used to let Home say "Everything looks good" and "Nothing
 * active yet" to a paying customer while billing and service inventory were
 * down. The tracker keeps the fallback (the page still renders) and remembers
 * WHICH reads failed, so the page can refuse a claim it could not check. */

// the facts an all-clear rests on, in the customer's words; the rest are extras
const SAID = {
  products: 'your plans', services: 'your services', bills: 'your bills',
  ccase: 'your payment status', usage: 'your usage', orders: 'your orders',
};

/** { soft, missing }: soft(name, promise, fallback) answers the fallback on failure and records the name. */
export function tracker() {
  const missing = [];
  return { missing, soft: (name, p, fallback) => p.catch(() => { missing.push(name); return fallback; }) };
}

/** What the page could not check, in the customer's words — only the facts an all-clear rests on. */
export const unchecked = (missing = []) => missing.filter((m) => SAID[m]).map((m) => SAID[m]);

/** "a", "a and b", "a, b and c" — `and` comes in already translated. */
export const listed = (words, and = 'and') => (words.length < 2 ? words.join('')
  : `${words.slice(0, -1).join(', ')} ${and} ${words[words.length - 1]}`);
