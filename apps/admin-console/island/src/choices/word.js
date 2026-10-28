/* The operator's own word for a Choice (#386) — "Choices" at GenAlpha, "Picks"
 * at Taranga — from the tenant config the console already loads. Every
 * sentence on these desks goes through here; none hard-codes the word. */
const configured = () => (typeof window !== 'undefined' && window.BSS_CONSOLE_CONFIG?.choicesLabel) || 'Choices';

/** The plural, as the operator writes it: "Picks". */
export const choices = () => configured();

/** The singular: "Pick" (an English plural loses its s; any other word is kept as is). */
export const choice = () => { const w = configured(); return /[^s]s$/i.test(w) ? w.slice(0, -1) : w; };

/** "1 Pick", "2 Picks". */
export const count = (n) => `${n} ${Number(n) === 1 ? choice() : choices()}`;
