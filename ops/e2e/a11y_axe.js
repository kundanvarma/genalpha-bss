/* Shared accessibility harness: inject axe-core into a live page and run it
 * against the WCAG 2.2 AA ruleset. Used by the baseline scanner and by the
 * per-portal a11y suites so every channel is held to the same bar.
 *
 * WCAG 2.2 AA = the legal target for an EU/Norwegian consumer portal. We
 * enforce it in CI so accessibility can't silently rot between releases.
 */
const path = require('path');

const WCAG_22_AA = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];

/** Inject axe and run it on the current page; returns the raw axe results. */
async function runAxe(page, { tags = WCAG_22_AA } = {}) {
  await page.addScriptTag({ path: require.resolve('axe-core') });
  return page.evaluate(async (runTags) => {
    // eslint-disable-next-line no-undef
    return await axe.run(document, {
      runOnly: { type: 'tag', values: runTags },
      resultTypes: ['violations'],
    });
  }, tags);
}

/** Flatten axe violations into a compact, sortable list of findings. */
function summarize(results) {
  const rows = [];
  for (const v of results.violations || []) {
    rows.push({
      id: v.id,
      impact: v.impact || 'n/a',
      help: v.help,
      wcag: (v.tags || []).filter((t) => t.startsWith('wcag')).join(','),
      nodes: v.nodes.length,
      sample: v.nodes[0] ? v.nodes[0].target.join(' ') : '',
    });
  }
  // worst impact first
  const order = { critical: 0, serious: 1, moderate: 2, minor: 3, 'n/a': 4 };
  rows.sort((a, b) => (order[a.impact] - order[b.impact]) || (b.nodes - a.nodes));
  return rows;
}

/** Count total violating nodes across all rules (the number that must reach 0). */
function nodeCount(results) {
  return (results.violations || []).reduce((n, v) => n + v.nodes.length, 0);
}

module.exports = { runAxe, summarize, nodeCount, WCAG_22_AA };
