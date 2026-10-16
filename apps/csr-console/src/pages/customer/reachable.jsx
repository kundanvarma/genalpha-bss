import { useState } from 'react';

/* EMPTY IS NOT UNAVAILABLE (#289). The 360's catch-up reads are fail-soft — a
 * dead optional component must not blank the page — but they used to fail into
 * `[]`, and `[]` rendered "Nothing to suggest." / "no agreements". An agent on a
 * call took that as a fact about the customer when it was a fact about the
 * fleet: bss-recommendation was down five days and the desk never said so.
 * Now the read throws, and the page keeps the two answers apart: what the
 * component SAID (possibly nothing), and which components it could not ASK. */

export const None = () => <span className="secnone"> — none</span>;

/** Heading badge for a card whose component did not answer. */
export const Unreachable = ({ what }) => (
  <span className="secnone error" data-testid="unreachable"> — could not reach {what}</span>
);

/** The card's one-line body when it holds no rows: the outage, or the real "none". */
export const EmptyOr = ({ down, what, children }) => (down
  ? <p className="error small" data-testid="unreachable">Could not reach {what}, so this is unknown — not empty.</p>
  : <p className="dim small">{children}</p>);

/**
 * [down, soft]: soft(scope, read, set) runs read(id); an answer goes to set and
 * clears the scope, a failure marks it — the last good answer stays on screen.
 */
export function useReach(id) {
  const [down, setDown] = useState({});
  const mark = (scope, value) => setDown((d) => (d[scope] === value ? d : { ...d, [scope]: value }));
  const soft = (scope, read, set) => read(id)
    .then((v) => { set(v); mark(scope, false); })
    .catch(() => mark(scope, true));
  return [down, soft];
}
