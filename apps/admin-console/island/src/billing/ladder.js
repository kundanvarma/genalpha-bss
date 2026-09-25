/* The collections ladder, in the words the service actually uses.
 *
 * The states are the service's own — current, reminded, warned, restricted,
 * suspended, terminated, writtenOff — and this file exists so the list and the
 * case cannot drift apart, and so no screen invents a rung. A case that was
 * cured is not a separate state: it returns to the foot of the ladder with
 * nothing owed and a date on it, which is why "is this case still running" is
 * a question about the money, not about the word.
 */

/** Each rung, said the way a collections agent would say it. */
export const LADDER = {
  current: 'at the foot of the ladder',
  reminded: 'a reminder has gone out',
  warned: 'a warning has been sent',
  restricted: 'service restricted',
  suspended: 'service suspended',
  terminated: 'service terminated',
  writtenOff: 'written off',
};

/** The same rung in two or three words, for a table cell. */
export const STAGE = {
  current: 'no step taken',
  reminded: 'reminded',
  warned: 'warned',
  restricted: 'restricted',
  suspended: 'suspended',
  terminated: 'terminated',
  writtenOff: 'written off',
};

const owed = (c) => Number(((c && c.overdueBalance) || {}).value || 0);

/** Is this case still work? Written off is finished, and so is a case that
 *  owes nothing — the ladder resets on a cure rather than marking a state,
 *  so counting by state alone reports cleared cases as open. */
export function isRunning(c) {
  return (c || {}).state !== 'writtenOff' && owed(c) > 0;
}

/** Why a case is not being chased right now, if it is not. */
export function holdWord(c, day) {
  const h = (c || {}).holds || {};
  if (h.promiseToPay) return `promise${h.promiseToPay.dueAt ? ` until ${day(h.promiseToPay.dueAt)}` : ''}`;
  if (h.dispute) return 'contested';
  if (h.hardship) return 'hardship';
  return '';
}

/** What is restricted, stated from the ladder when no service is named.
 *  A suspended case with an empty service list still means the customer is
 *  cut off; saying "nothing is restricted" beside "suspended" is a screen
 *  contradicting itself. */
export function restriction(c) {
  const services = (c && c.enforcedServices) || [];
  const state = (c || {}).state;
  const named = services.length
    ? `${services.length} ${services.length === 1 ? 'service is' : 'services are'} held under the case.`
    : '';
  if (state === 'suspended') return `Service is suspended while the debt stands. ${named}`.trim();
  if (state === 'restricted') return `Service is restricted — calls barred and data throttled, emergency numbers still reachable. ${named}`.trim();
  if (state === 'terminated') return `Service has been terminated. ${named}`.trim();
  return named;
}
