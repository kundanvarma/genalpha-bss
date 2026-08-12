/*
 * Claude market provider — the fleet's first LLM-INSIDE-A-SEAM adapter.
 *
 * Same wire as mock-market (the Product Advisor cannot tell them apart):
 *   GET /health -> { status:'UP', mode }
 *   GET /offers (Bearer) -> [{ competitor, name, dataGb, price:{unit,value}, notes }]
 *
 * What's different is INSIDE: a messy raw tariff dataset (mixed markets, junk
 * rows, inconsistent fields — data/tariffs-raw.json, the stand-in for a real
 * scraped/purchased feed) is CURATED by Claude down to the operator's market
 * and segment. The model only SELECTS source rows and names the competitor
 * segment — every emitted name/price/dataGb is then EXTRACTED AND COPIED from
 * the source row by CODE, never taken from the model's output. A row id the
 * model hallucinates simply doesn't exist in the source and is dropped. The
 * pairing shows its work: notes carries `src:<rowId> · as-of <date>`.
 *
 * Guardrails, in order:
 *  - numbers copied + verified, never generated (the code path, not a prompt);
 *  - FAIL-OPEN: no ANTHROPIC_API_KEY / API error -> a deterministic rule-based
 *    curation of the same source (mode:'deterministic'), the seam never breaks;
 *  - the curation is cached (TTL) so the model is consulted, not hammered;
 *  - the key comes from env at deploy; it is never logged and never in config.
 */
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const { URL } = require('url');

const PORT = process.env.PORT || 8080;
const MARKET = process.env.MARKET || 'NO';          // the operator's market
const SEGMENT = process.env.SEGMENT || 'consumer mobile';
const MODEL = process.env.MODEL || 'claude-haiku-4-5-20251001';
const KEY = process.env.ANTHROPIC_API_KEY || '';
const TTL_MS = Number(process.env.CURATE_TTL_MS || 10 * 60 * 1000);

const RAW = JSON.parse(fs.readFileSync(path.join(__dirname, 'data', 'tariffs-raw.json'), 'utf8'));
const AS_OF = RAW.asOf;
const byId = new Map(RAW.rows.map((r) => [r.id, r]));

let cache = null; // { offers, mode, at }

/** The only path to an emitted offer: COPY the fields from the source row. */
function offerFromSource(rowId, competitor) {
  const src = byId.get(rowId);
  if (!src) return null;                                    // hallucinated id -> dropped
  const gb = Number(src.data_gb ?? src.dataGb ?? src.gb);
  const price = Number(src.monthly_price ?? src.price);
  if (!src.plan_name || !Number.isFinite(gb) || !Number.isFinite(price)) return null;
  return {
    competitor: competitor || src.operator || 'unknown',
    name: String(src.plan_name),                            // copied, not generated
    dataGb: gb,                                             // copied, not generated
    price: { unit: String(src.currency || 'EUR'), value: price }, // copied, not generated
    notes: `src:${rowId} · as-of ${AS_OF}`,                 // the pairing shows its work
  };
}

/** Deterministic curation — the fail-open path and the model's baseline:
 * same market, strictly consumer-mobile rows with a real GB + price > 0.
 * The model's added value over this is judgment (segment nuance, tier
 * grouping, junk the rules can't name) — never different numbers. */
function deterministicCuration() {
  return RAW.rows
    .filter((r) => String(r.market || '').toUpperCase() === MARKET)
    .filter((r) => String(r.type || '').toLowerCase() === 'mobile')
    .filter((r) => Number(r.monthly_price ?? r.price) > 0)
    .filter((r) => !/draft|do not publish/i.test(String(r.plan_name || '')))
    .map((r) => offerFromSource(r.id, r.operator))
    .filter(Boolean);
}

/** Ask Claude to SELECT relevant source rows (ids only) for the market/segment. */
async function modelCuration() {
  const prompt = 'You curate a raw tariff dataset for a telecom price-comparison feed.\n'
    + `Market: ${MARKET}. Segment: ${SEGMENT}.\n`
    + 'From the JSON rows below, select ONLY rows that are real competitor offers in this '
    + 'market and segment (drop junk, drafts, wrong-market, business-only and non-mobile rows). '
    + 'Group tiers of the same operator together.\n'
    + 'Answer with STRICT JSON only: {"selection":[{"id":"<row id>","competitor":"<display name>"}]}\n\n'
    + JSON.stringify(RAW.rows);
  const res = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: {
      'x-api-key': KEY,
      'anthropic-version': '2023-06-01',
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      model: MODEL, max_tokens: 1500,
      messages: [{ role: 'user', content: prompt }],
    }),
  });
  if (!res.ok) throw new Error(`anthropic ${res.status}`);
  const body = await res.json();
  const text = (body.content || []).map((c) => c.text || '').join('');
  const match = text.match(/\{[\s\S]*\}/);
  const selection = JSON.parse(match ? match[0] : text).selection || [];
  // THE GUARANTEE: the model chose; the SOURCE speaks. Copy every field from
  // the raw row; a wrong/invented id yields null and is dropped.
  return selection.map((s) => offerFromSource(String(s.id), s.competitor)).filter(Boolean);
}

async function curated() {
  if (cache && Date.now() - cache.at < TTL_MS) return cache;
  let offers; let mode;
  if (KEY) {
    try {
      offers = await modelCuration();
      mode = 'model';
      if (!offers.length) { offers = deterministicCuration(); mode = 'deterministic'; }
    } catch (e) {
      console.log(`[market-claude] model curation failed (${e.message}) — deterministic fallback`);
      offers = deterministicCuration();
      mode = 'deterministic';
    }
  } else {
    offers = deterministicCuration();
    mode = 'deterministic';
  }
  cache = { offers, mode, at: Date.now() };
  console.log(`[market-claude] curated ${offers.length} offer(s) [${mode}] from ${RAW.rows.length} raw rows`);
  return cache;
}

http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const json = (code, body) => {
    res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  };
  if (req.method === 'GET' && url.pathname === '/health') {
    return json(200, { status: 'UP', mode: cache ? cache.mode : (KEY ? 'model (lazy)' : 'deterministic') });
  }
  if (req.method === 'GET' && url.pathname === '/source') {
    // the raw dataset, exposed so a verifier can check every offer against it
    return json(200, RAW);
  }
  if (req.method === 'GET' && url.pathname === '/offers') {
    const auth = req.headers.authorization || '';
    if (!auth.startsWith('Bearer ') || auth.length <= 7) return json(401, { error: 'subscription token required' });
    const { offers } = await curated();
    return json(200, offers);
  }
  json(404, { error: 'not found' });
}).listen(PORT, () => console.log(`market-provider-claude on ${PORT} (market=${MARKET}, model key ${KEY ? 'present' : 'ABSENT -> deterministic'})`));
