/* The desk says what the catalog declares, and guesses only when it must. Suite #254.
 *
 * #143 step 1, which #150 is sequenced behind. The CSR desk decided what a
 * service WAS by pattern-matching its name against four regular expressions and
 * falling through to the word "Service". The catalog already knew: every
 * customer-facing service specification carries `fulfilmentFamily`. The desk
 * simply never asked.
 *
 * The case that proves it is on the fleet today. A customer's **Kids
 * Smartwatch** is realised by the CFS **Device shipment**, which declares
 * `family=device`. Run that name through the old guesser — no mobile, no
 * broadband, no tv, no voice — and it returns `other`, so the chip read
 * "Service". The catalog had the answer the whole time.
 *
 * This suite needs NO FLEET. `serviceKind` is a pure function of a service row
 * and the declared families, so it is exercised directly: the module is bundled
 * with esbuild (the same transform the app build uses) and called. That makes
 * the rule breakable on purpose in seconds rather than in a container rebuild,
 * which is the point of having it.
 *
 * What is proven:
 *  - THE DECLARED ANSWER WINS. A row whose name says nothing useful is typed by
 *    its family: the Kids Smartwatch becomes a Device, not a "Service".
 *  - IT OVERRIDES A WRONG GUESS. A service named "Fiber 1000 Home Hub" whose
 *    spec declares `tv` is a TV service. The name used to decide; it no longer
 *    does. This is the same contradiction suite #228 proves for fulfilment.
 *  - THE GUESS SURVIVES WHERE NOTHING IS DECLARED. A legacy row pointing at a
 *    derived `svcspec-broadband` stand-in still reads Broadband, because the
 *    fallback is the fallback and not a regression.
 *  - EVERY FAMILY HAS A WORD. All eight families the catalog can declare map to
 *    something an agent would say; "Service" is now only what an undeclared
 *    service gets, never the catch-all it was.
 *
 * HONEST LIMIT: this proves the rule, not the wiring. That Customer360 primes
 * the map on load is asserted by nothing here — it is one call in one effect,
 * and the browser proof belongs with #150's nesting work, which is sequenced
 * directly behind this.
 */
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const SRC = path.resolve(__dirname, '../../apps/csr-console/src/pages/customer/ServiceRows.jsx');
// the bundle lands inside the app so its `react` externals resolve from the
// app's own node_modules — the same ones the console is built against
const OUT = path.resolve(__dirname, '../../apps/csr-console/.service-kind-bundle.cjs');
const fail = (m) => { throw new Error(m); };
const ok = (m) => console.log('OK ' + m);

try {
  // the app's own transform, so this tests the code the console ships
  execFileSync('npx', ['--yes', 'esbuild', SRC, '--bundle', '--format=cjs', '--jsx=automatic',
    '--external:react', '--external:react/jsx-runtime', '--log-level=error', `--outfile=${OUT}`],
  { cwd: path.resolve(__dirname, '../../apps/csr-console'), stdio: ['ignore', 'ignore', 'inherit'] });

  // the module graph reaches the browser's config object at load; the function
  // under test is pure, so a stub is enough and nothing here calls the network
  global.window = { BSS_CSR_CONFIG: {}, location: { origin: 'http://localhost' } };
  global.document = { documentElement: {} };
  const { serviceKind, KIND_WORDS, primeFulfilmentFamilies } = require(OUT);

  /* ---------- the families the catalog declares, as it declares them ---------- */
  const DEVICE_CFS = 'bc5051cf-device-shipment';
  const TV_CFS = '12a0395a-tv-entitlement';
  primeFulfilmentFamilies({
    [DEVICE_CFS]: 'device',
    [TV_CFS]: 'tv',
    'cfs-mobile': 'mobile',
    'cfs-internet': 'internet',
    'cfs-security': 'security',
    'cfs-compute': 'compute',
    'cfs-partner': 'partner',
    'cfs-billing': 'billing-only',
  });

  /* ---------- the declared answer wins where the name says nothing ---------- */
  const watch = { name: 'Kids Smartwatch', serviceSpecification: { id: DEVICE_CFS, name: 'Device shipment' } };
  const kind = serviceKind(watch);
  if (kind === 'other') {
    fail('the Kids Smartwatch still falls through to "Service" — the desk is guessing, not reading');
  }
  if (KIND_WORDS[kind] !== 'Device') fail(`the Kids Smartwatch reads "${KIND_WORDS[kind]}", not Device`);
  ok('a row the name cannot type is typed by its declared family: Kids Smartwatch → Device');

  /* ---------- the declared answer beats a WRONG guess ---------- */
  const mislabelled = { name: 'Fiber 1000 Home Hub', serviceSpecification: { id: TV_CFS, name: 'TV entitlement' } };
  const guessed = serviceKind({ name: 'Fiber 1000 Home Hub', serviceSpecification: { id: 'unknown' } });
  if (guessed !== 'broadband') fail(`the fallback no longer reads that name as broadband (${guessed})`);
  const declared = serviceKind(mislabelled);
  if (declared !== 'tv') {
    fail(`a service the catalog calls tv reads as "${declared}" because its NAME says fibre`
      + ' — the name is deciding again');
  }
  ok('and it beats a wrong guess: a tv-declared service named "Fiber 1000 Home Hub" is TV, not Broadband');

  /* ---------- the guess survives where nothing is declared ---------- */
  const legacy = { name: 'Heritage DSL 20', serviceSpecification: { id: 'svcspec-broadband', name: 'broadband service' } };
  if (serviceKind(legacy) !== 'broadband') fail('a service with no declared family lost its fallback');
  const bare = { name: 'Something nobody mapped', serviceSpecification: { id: 'svcspec-service' } };
  if (serviceKind(bare) !== 'other') fail('a service nothing can type should still read as a plain Service');
  ok('where nothing is declared the guess still answers, and an untypeable row is still "Service"');

  /* ---------- every family the catalog can declare has a word ---------- */
  const families = ['mobile', 'internet', 'tv', 'voice', 'security', 'compute', 'partner', 'billing-only', 'device'];
  const missing = [];
  for (const family of families) {
    const k = serviceKind({ name: 'x', serviceSpecification: { id: `probe-${family}` } });
    primeFulfilmentFamilies({ ...{ [`probe-${family}`]: family } });
    const typed = serviceKind({ name: 'x', serviceSpecification: { id: `probe-${family}` } });
    if (typed === 'other' || !KIND_WORDS[typed]) missing.push(family);
    void k;
  }
  if (missing.length) {
    fail(`these declared families have no word on the desk and would read as "Service": ${missing.join(', ')}`);
  }
  ok(`all ${families.length} declarable families have an agent's word — "Service" is now only for the undeclared`);

  console.log('\nPASS service_kind_test — the desk reads the declared family, and guesses only when there is none');
} finally {
  fs.rmSync(OUT, { force: true });
}
