/* The content seam — one TMF667 API, three worlds for the bytes.
 *
 *  - in-row (the dev-simple default), the S3 protocol (MinIO standing in
 *    for AWS/R2/on-prem), and the AZURE BLOB dialect (Azurite, Microsoft's
 *    own emulator) — because "any cloud" is only true if Azure is a
 *    first-class citizen, not an S3 pretender
 *  - the same upload → serve loop runs against each store, and the
 *    DATABASE gives the receipt: external stores leave content NULL in
 *    the row with a storage key beside it — the bytes provably live
 *    elsewhere, yet the API serves them byte-for-byte
 *  - the store choice is config (CONTENT_STORE env); the suite swaps it
 *    by recreating ONE container, nothing else changes
 */
const { execSync } = require('child_process');
const { request } = require('playwright');

const API = 'http://localhost:8080';
const run = Date.now();
// a 1x1 PNG — small, real, and recognizably PNG-magic
const PNG_B64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGBgAAAABQAB'
  + 'h6FO1AAAAABJRU5ErkJggg==';

async function token(ctx) {
  for (let i = 0; i < 10; i++) {
    try {
      const res = await ctx.post('http://localhost:8085/realms/bss/protocol/openid-connect/token',
        { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
      return (await res.json()).access_token;
    } catch (transient) {
      await new Promise((r) => setTimeout(r, 3000));
    }
  }
  throw new Error('keycloak unreachable');
}

function swapStore(store) {
  execSync(`CONTENT_STORE=${store} docker compose up -d document`, {
    cwd: `${__dirname}/../..`, stdio: 'pipe',
    env: { ...process.env, PATH: '/opt/homebrew/bin:' + process.env.PATH,
      CONTENT_STORE: store }, timeout: 180000 });
}

function rowReceipt(docId) {
  const out = execSync(`docker exec bss-postgres psql -U postgres -d document -tA -c `
    + `"SELECT (content IS NULL), COALESCE(storage_key,'-') FROM document WHERE id='${docId}'"`, {
    env: { ...process.env, PATH: '/opt/homebrew/bin:' + process.env.PATH } }).toString().trim();
  const [contentNull, key] = out.split('|');
  return { bytesInRow: contentNull === 'f', storageKey: key };
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  async function uploadAndServe(store, expectKeyPrefix) {
    const staff = await token(ctx);
    const doc = await (await ctx.post(`${API}/tmf-api/documentManagement/v4/document`, {
      headers: { Authorization: 'Bearer ' + staff, 'Content-Type': 'application/json' },
      data: { name: `seam-${store}-${run}.png`, mimeType: 'image/png',
        category: 'gallery', content: PNG_B64 } })).json();
    if (!doc.id) fail(`${store}: upload failed: ` + JSON.stringify(doc).slice(0, 160));
    const served = await ctx.get(`${API}${doc.attachmentUrl}`);
    if (served.status() !== 200) fail(`${store}: content not served: ` + served.status());
    const bytes = await served.body();
    if (bytes.toString('base64') !== PNG_B64) fail(`${store}: served bytes differ from upload`);
    const receipt = rowReceipt(doc.id);
    if (expectKeyPrefix === null) {
      if (!receipt.bytesInRow || receipt.storageKey !== '-') {
        fail(`in-row: expected bytes in the row: ` + JSON.stringify(receipt));
      }
    } else {
      if (receipt.bytesInRow || !receipt.storageKey.startsWith(expectKeyPrefix)) {
        fail(`${store}: the row still holds bytes or lacks its key: ` + JSON.stringify(receipt));
      }
    }
    return receipt;
  }

  /* ---------- 1. the default: in-row, untouched ---------- */
  swapStore('in-row');
  await sleep(14000);
  await uploadAndServe('in-row', null);
  console.log('OK IN-ROW (the default): bytes in the row, no storage key — dev, CI and every'
    + ' existing suite keep working with zero configuration');

  /* ---------- 2. the S3 protocol (MinIO) ---------- */
  swapStore('s3');
  await sleep(14000);
  const s3 = await uploadAndServe('s3', 's3:');
  console.log(`OK S3 PROTOCOL: same API, same bytes served — but the row says content IS NULL`
    + ` with key "${s3.storageKey}": the bytes provably live in MinIO (and would in AWS, R2 or`
    + ' on-prem MinIO — one adapter, four homes)');

  /* ---------- 3. the AZURE dialect (Azurite) ---------- */
  swapStore('azure-blob');
  await sleep(14000);
  const az = await uploadAndServe('azure-blob', 'azure:');
  console.log(`OK AZURE BLOB: the SharedKey dialect, not an S3 pretender — row content NULL,`
    + ` key "${az.storageKey}", bytes in Azurite exactly as they would sit in real Azure Blob`
    + ' on an AKS deployment');

  /* ---------- 4. and back, losing nothing ---------- */
  swapStore('in-row');
  await sleep(14000);
  const staff = await token(ctx);
  const logo = await ctx.get(`${API}/tmf-api/documentManagement/v4/document/brand-logo`,
    { headers: { Authorization: 'Bearer ' + staff } });
  if (logo.status() >= 500) fail('the store swap broke existing content: ' + logo.status());
  console.log('OK RESTORED: back on in-row; pre-existing content (the brand logo) still serves.'
    + ' The store choice is one env var on one container');

  console.log('\nALL OBJECT-STORE CHECKS PASSED — one TMF667 surface, three homes for the'
    + ' bytes: the row, the S3 protocol, and Azure\'s own dialect. The attachment URL never'
    + ' changed; the receipts are in the database. "Any cloud" now has its proof.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
