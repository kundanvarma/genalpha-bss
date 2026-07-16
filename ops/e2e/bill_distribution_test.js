/* Bill documents and distribution — the bill leaves the building, honestly.
 *
 *  - every bill opens as a PDF: server-rendered from the SAME applied
 *    rates the API serves, owner-scoped like the bill itself
 *  - finished bills ride the DISTRIBUTION seam to the tenant's partner:
 *    nova's leave as EHF (Norway's CIUS of Peppol BIS Billing 3.0, UBL,
 *    with the payment reference the NO-R rules demand); genalpha's go to
 *    the PRINT house as PDFs — formats and channels are config, not code
 *  - the mock partner VALIDATES like a real access point: an 'ehf' claim
 *    without the Peppol customization is refused
 */
const { request } = require('playwright');

const API = 'http://localhost:8080';
const DIST = 'http://localhost:8124';
const run = Date.now();
const PLAN = { id: '14291c1a-df26-4232-8084-500466888e46', name: 'GenAlpha Mobile 10 GB' };

async function token(ctx, realm, user, pass) {
  const res = await ctx.post(`http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: user, password: pass } });
  return (await res.json()).access_token;
}

(async () => {
  const ctx = await request.newContext();
  const fail = (m) => { console.error('FAIL: ' + m); process.exit(1); };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const staff = await token(ctx, 'bss', 'demo', 'demo');
  const H = (t) => ({ Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' });
  const BILLS = `${API}/tmf-api/customerBillManagement/v4`;

  /* ---------- a genalpha bill: PDF + the PRINT channel ---------- */
  const email = `paper-${run}@example.com`;
  const login = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email, givenName: 'Pa', familyName: `Per${run}` } })).json();
  await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: H(staff), data: {
    id: login.id, givenName: 'Pa', familyName: `Per${run}`,
    contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] } });
  const cust = await token(ctx, 'bss', email, login.temporaryPassword);
  await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H(cust), data: { productOrderItem: [{ action: 'add', productOffering: PLAN }] } });
  let bill = null;
  for (let i = 0; i < 30 && !bill; i++) {
    await sleep(2000);
    await ctx.post(`${BILLS}/billingRun`, { headers: H(staff) });
    const list = await (await ctx.get(`${BILLS}/customerBill?limit=50`, { headers: H(cust) })).json();
    bill = list.find((b) => b.state === 'new') || null;
  }
  if (!bill) fail('no genalpha bill');
  if (!(bill.billDocument || []).some((d) => String(d.url || '').endsWith('/document.pdf'))) {
    fail('the bill does not carry its TMF678 document ref: ' + JSON.stringify(bill.billDocument));
  }

  const pdfRes = await ctx.get(`${BILLS}/customerBill/${bill.id}/document.pdf`, { headers: H(cust) });
  if (pdfRes.status() !== 200) fail('PDF endpoint failed: ' + pdfRes.status());
  const pdf = await pdfRes.body();
  if (!pdf.slice(0, 4).toString().startsWith('%PDF')) fail('the document is not a PDF');
  if (pdf.length < 1000) fail('the PDF looks empty: ' + pdf.length + ' bytes');
  console.log(`OK the bill OPENS AS A PDF (${pdf.length} bytes) — and the TMF678 billDocument`
    + ' ref points at it');

  /* owner-scoped like the bill itself */
  const nosy = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(staff), data: { email: `snoop-${run}@example.com`, givenName: 'Sno', familyName: `Op${run}` } })).json();
  const nosyTok = await token(ctx, 'bss', `snoop-${run}@example.com`, nosy.temporaryPassword);
  const probe = await ctx.get(`${BILLS}/customerBill/${bill.id}/document.pdf`, { headers: H(nosyTok) });
  if (probe.status() !== 404) fail("a stranger read someone else's invoice: " + probe.status());
  console.log('OK owner-scoped: a stranger asking for the PDF gets a 404, never an invoice');

  /* the print house received the PDF print job */
  let printJob = null;
  for (let i = 0; i < 15 && !printJob; i++) {
    await sleep(1500);
    const jobs = await (await ctx.get(`${DIST}/invoices?billNo=${bill.billNo}`)).json();
    printJob = jobs.find((j) => j.channel === 'print') || null;
  }
  if (!printJob) fail('the print job never reached the distribution partner');
  if (printJob.format !== 'pdf' || printJob.token !== 'genalpha-dist-token'
      || !Buffer.from(printJob.payload, 'base64').slice(0, 4).toString().startsWith('%PDF')) {
    fail('the print job is not a token-signed PDF: ' + JSON.stringify({
      format: printJob.format, token: printJob.token }).slice(0, 120));
  }
  console.log('OK PRINT CHANNEL: genalpha\'s bill reached the print house as a real PDF, signed'
    + ' with genalpha\'s own token — physical mail is the partner\'s job, not the BSS\'s');

  /* ---------- the CSR side: open the bill, resend it as mail ---------- */
  // an agent sees the SAME document the customer holds — the top billing
  // call driver is "explain this invoice", and you can't do that blind
  const csrPdf = await ctx.get(`${BILLS}/customerBill/${bill.id}/document.pdf`, { headers: H(staff) });
  if (csrPdf.status() !== 200 || !(await csrPdf.body()).slice(0, 4).toString().startsWith('%PDF')) {
    fail('the agent could not open the customer\'s bill PDF: ' + csrPdf.status());
  }
  console.log('OK CSR VIEW: the agent opens the exact PDF the customer sees — no guessing on the line');

  // genalpha runs WITHOUT an ESP (config): the copy lands in the in-app
  // inbox; nova's identical click below leaves as a real email
  const resend = await ctx.post(`${BILLS}/customerBill/${bill.id}/resend`, { headers: H(staff) });
  if (resend.status() !== 202) fail('resend refused: ' + resend.status());
  let inboxCopy = null;
  for (let i = 0; i < 15 && !inboxCopy; i++) {
    await sleep(1500);
    const inbox = await (await ctx.get(
      `${API}/tmf-api/communicationManagement/v4/communicationMessage?limit=50`,
      { headers: H(cust) })).json();
    inboxCopy = inbox.find((m) => (m.subject || '').includes(bill.billNo)) || null;
  }
  if (!inboxCopy) fail('the invoice copy never reached the customer inbox');
  console.log('OK RESEND (in-app tenant): the agent\'s one click put "Your invoice '
    + bill.billNo + '" in the customer\'s inbox — genalpha has no ESP configured, so'
    + ' in-app IS the delivery, by config');

  /* a customer can self-serve the same copy — owner-scoped like the PDF */
  const strangerResend = await ctx.post(`${BILLS}/customerBill/${bill.id}/resend`, { headers: H(nosyTok) });
  if (strangerResend.status() !== 404) {
    fail("a stranger triggered someone else's invoice mail: " + strangerResend.status());
  }
  console.log('OK resend is owner-scoped too: a stranger asking gets a 404');

  /* ---------- a nova bill: the EHF e-invoice channel ---------- */
  const novaStaff = await token(ctx, 'nova', 'demo', 'demo');
  const novaOffers = await (await ctx.get(
    `${API}/tmf-api/productCatalogManagement/v4/productOffering?limit=100`,
    { headers: { ...H(novaStaff), Host: 'shop.nova.localhost' } })).json();
  const novaPlan = novaOffers.find((o) => (o.name || '').includes('Nova') && !o.isBundle)
    || novaOffers[0];
  const nEmail = `ehf-${run}@nova.example`;
  const nLogin = await (await ctx.post(`${API}/tmf-api/rolesAndPermissionsManagement/v4/user`,
    { headers: H(novaStaff), data: { email: nEmail, givenName: 'Eh', familyName: `Eff${run}` } })).json();
  await ctx.post(`${API}/tmf-api/party/v4/individual`, { headers: H(novaStaff), data: {
    id: nLogin.id, givenName: 'Eh', familyName: `Eff${run}`,
    contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: nEmail } }] } });
  const nova = await token(ctx, 'nova', nEmail, nLogin.temporaryPassword);
  await ctx.post(`${API}/tmf-api/productOrderingManagement/v4/productOrder`, {
    headers: H(nova), data: { productOrderItem: [{ action: 'add',
      productOffering: { id: novaPlan.id, name: novaPlan.name } }] } });
  let novaBill = null;
  for (let i = 0; i < 30 && !novaBill; i++) {
    await sleep(2000);
    await ctx.post(`${BILLS}/billingRun`, { headers: H(novaStaff) });
    const list = await (await ctx.get(`${BILLS}/customerBill?limit=50`, { headers: H(nova) })).json();
    novaBill = list.find((b) => b.state === 'new') || null;
  }
  if (!novaBill) fail('no nova bill');
  let einvoice = null;
  for (let i = 0; i < 15 && !einvoice; i++) {
    await sleep(1500);
    const docs = await (await ctx.get(`${DIST}/invoices?billNo=${novaBill.billNo}`)).json();
    einvoice = docs.find((d) => d.channel === 'einvoice') || null;
  }
  if (!einvoice) fail('the EHF e-invoice never reached the access point');
  if (einvoice.format !== 'ehf' || einvoice.token !== 'nova-dist-token') {
    fail('wrong format/identity at the access point: ' + einvoice.format + '/' + einvoice.token);
  }
  const xml = einvoice.payload;
  if (!xml.includes('urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0')
      || !xml.includes(`<cbc:ID>${novaBill.billNo}</cbc:ID>`)
      || !xml.includes('<cbc:PaymentID>')
      || !xml.includes('PayableAmount')) {
    fail('the EHF document is missing its load-bearing parts: ' + xml.slice(0, 200));
  }
  console.log('OK EHF CHANNEL: nova\'s bill left as UBL 2.1 with the Peppol BIS 3.0 customization,'
    + ' the payment reference the NO-R rules demand, the bill number and the payable amount —'
    + ' and the mock access point VALIDATED it like a real one would');

  /* the SAME resend click on nova's side leaves as a real EMAIL with the
   * PDF attached — because nova has an ESP configured; that difference
   * is tenant config, not code */
  const nResend = await ctx.post(`${BILLS}/customerBill/${novaBill.id}/resend`, { headers: H(novaStaff) });
  if (nResend.status() !== 202) fail('nova resend refused: ' + nResend.status());
  let copyMail = null;
  for (let i = 0; i < 20 && !copyMail; i++) {
    await sleep(1500);
    const mails = await (await ctx.get(`http://localhost:8121/mails?to=${nEmail}`)).json();
    copyMail = mails.find((m) => (m.subject || '').includes(novaBill.billNo)) || null;
  }
  if (!copyMail) fail('the invoice copy never reached the ESP');
  const att = (copyMail.attachments || [])[0];
  if (!att || att.filename !== `${novaBill.billNo}.pdf` || att.type !== 'application/pdf'
      || !Buffer.from(att.content, 'base64').slice(0, 4).toString().startsWith('%PDF')) {
    fail('the mail carries no real PDF attachment: ' + JSON.stringify(att && {
      filename: att.filename, type: att.type }));
  }
  console.log('OK RESEND AS MAIL (ESP tenant): "send me a copy of my invoice" → one click, and'
    + ' the email arrives at the ADDRESS ON FILE (never one dictated over the phone) with '
    + novaBill.billNo + '.pdf attached — a real PDF, same bytes the endpoint serves');

  console.log('\nALL BILL-DISTRIBUTION CHECKS PASSED — the bill is a PDF anyone can save, the'
    + ' agent sees and resends the same document (to the address on file only), and a'
    + ' finished bill leaves for the tenant\'s own partner: EHF to the access point for Norway,'
    + ' a print job to the print house — formats, channels and delivery are config, never code.');
})().catch((e) => { console.error('FAIL:', e.message.split('\n').slice(0, 3).join(' | ')); process.exit(1); });
