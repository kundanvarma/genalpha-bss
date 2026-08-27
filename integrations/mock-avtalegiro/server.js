/*
 * Mock AvtaleGiro-style direct-debit rail — the bank side of the loop.
 * File-batch honest, like the real thing (Mastercard Payment Services /
 * Nets): mandates travel in BATCH FILES (the bank's word, never a console
 * edit), claims arrive from the biller per cycle, and settlement goes home
 * as Nets-OCR-style fixed-width lines the biller's remittance door already
 * speaks.
 *
 *   GET  /health                     -> probe
 *   POST /mandates {partyRef, accountRef}   -> the customer signs up at
 *        their bank (e2e plays the bank); queued for the next mandate file
 *   POST /mandates/cancel {partyRef}        -> queues a delete record
 *   GET  /mandateFile                -> {records:[{action, partyRef,
 *        accountRef}]} — the batch the biller ingests (idempotent to
 *        re-fetch: the biller's upsert makes a re-posted file harmless)
 *   POST /claims {kid, billNo, partyRef, accountRef, amount, dueDate}
 *        -> 201 stores the cycle claim
 *   GET  /claims                     -> stored claims for e2e asserts
 *   GET  /settlementFile             -> text/plain OCR-style settlement
 *        lines for every unsettled claim (NY000010 batch header +
 *        NY09xx30 amount items, amounts in oere, KID right-adjusted at
 *        51-75) — marks those claims settled; empty 204 when nothing due
 *
 * In-memory; a seam target — the real adapter is a certified bank-file
 * exchange, config only.
 */
'use strict';

const http = require('http');

const PORT = process.env.PORT || 8080;

const MANDATES = []; // {action, partyRef, accountRef}
const CLAIMS = [];   // {kid, billNo, partyRef, accountRef, amount, dueDate, settled}
let batchSeq = 1000000;

const pad = (s, len, ch, left) => {
  s = String(s == null ? '' : s);
  if (s.length >= len) return s.slice(0, len);
  const fill = ch.repeat(len - s.length);
  return left ? fill + s : s + fill;
};

// One OCR "amount item 1" line: NY + service 09 + tx 15 + record 30,
// bank ref at 9-15, amount in oere at 34-50, KID right-adjusted at 51-75.
const ocrLineOf = (claim, idx) => {
  const oere = Math.round(Number(claim.amount && claim.amount.value ? claim.amount.value : claim.amount) * 100);
  return 'NY' + '09' + '15' + '30'
    + pad(1000000 + idx, 7, '0', true)
    + pad('', 18, '0', false)
    + pad(oere, 17, '0', true)
    + pad(claim.kid, 25, ' ', true)
    + pad('', 5, '0', false);
};

const server = http.createServer((req, res) => {
  const send = (code, body, type) => {
    const data = Buffer.from(typeof body === 'string' ? body : JSON.stringify(body));
    res.writeHead(code, { 'Content-Type': type || 'application/json', 'Content-Length': data.length });
    res.end(data);
  };
  const url = new URL(req.url, 'http://localhost');
  const withBody = (fn) => {
    let raw = '';
    req.on('data', (c) => { raw += c; });
    req.on('end', () => {
      let body;
      try { body = JSON.parse(raw || '{}'); } catch { return send(400, { error: 'invalid JSON' }); }
      fn(body);
    });
  };

  if (req.method === 'GET' && url.pathname === '/health') return send(200, { status: 'UP' });

  if (req.method === 'POST' && url.pathname === '/mandates') {
    return withBody((body) => {
      if (!body.partyRef || !body.accountRef) return send(400, { error: 'partyRef and accountRef required' });
      MANDATES.push({ action: 'add', partyRef: body.partyRef, accountRef: body.accountRef });
      return send(201, { action: 'add', partyRef: body.partyRef, accountRef: body.accountRef });
    });
  }

  if (req.method === 'POST' && url.pathname === '/mandates/cancel') {
    return withBody((body) => {
      if (!body.partyRef) return send(400, { error: 'partyRef required' });
      MANDATES.push({ action: 'delete', partyRef: body.partyRef });
      return send(201, { action: 'delete', partyRef: body.partyRef });
    });
  }

  if (req.method === 'GET' && url.pathname === '/mandateFile') {
    return send(200, { records: MANDATES });
  }

  if (req.method === 'POST' && url.pathname === '/claims') {
    return withBody((body) => {
      if (!body.kid || !body.amount) return send(400, { error: 'kid and amount required' });
      const claim = { ...body, settled: false, receivedAt: new Date().toISOString() };
      CLAIMS.push(claim);
      return send(201, claim);
    });
  }

  if (req.method === 'GET' && url.pathname === '/claims') {
    const kid = url.searchParams.get('kid');
    return send(200, CLAIMS.filter((c) => !kid || c.kid === kid));
  }

  if (req.method === 'GET' && url.pathname === '/settlementFile') {
    const due = CLAIMS.filter((c) => !c.settled);
    if (due.length === 0) return send(204, '');
    batchSeq += 1;
    const header = 'NY000010' + '0'.repeat(8) + pad(batchSeq, 7, '0', true) + '0'.repeat(57);
    const lines = due.map((c, i) => { c.settled = true; c.settledAt = new Date().toISOString(); return ocrLineOf(c, i); });
    return send(200, [header].concat(lines).join('\n') + '\n', 'text/plain');
  }

  return send(404, { error: 'not found' });
});

server.listen(PORT, () => console.log(`mock-avtalegiro on :${PORT}`));
