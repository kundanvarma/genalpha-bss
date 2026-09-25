import { createRoot } from 'react-dom/client';
import { Islands } from './islands.jsx';

/* window.mountIsland(name, element, context) — the console's only entry into
 * React. The shell calls it from a desk's custom pane; it keeps one root per
 * element so switching pages and coming back does not leak roots. Anything the
 * island needs from the shell (the signed-in user, a tenant's words, a fetch
 * that carries the token) arrives in `context`; the island imports nothing
 * from the vanilla files. */
const roots = new WeakMap();

function mountIsland(name, element, context = {}) {
  const Island = Islands[name];
  if (!Island) {
    // an unknown island is a wiring mistake, and the page says so rather than blanking
    element.textContent = `This screen (${name}) is not built yet.`;
    return null;
  }
  let root = roots.get(element);
  if (!root) {
    root = createRoot(element);
    roots.set(element, root);
  }
  // The key carries the VIEW as well as the island's name. Several tabs may
  // share one island — Journal and Chart of accounts are one Accounting page —
  // and clicking a tab is a navigation, so the screen starts again on the part
  // that tab named instead of keeping the state of the one before it.
  root.render(<Island key={`${name}:${context.view || ''}`} {...context} />);
  return root;
}

function unmountIsland(element) {
  const root = roots.get(element);
  if (root) {
    root.unmount();
    roots.delete(element);
  }
}

window.mountIsland = mountIsland;
window.unmountIsland = unmountIsland;
window.consoleIslands = Object.keys(Islands);

export { mountIsland, unmountIsland };
