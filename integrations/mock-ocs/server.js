/*
 * Mock OCS — stands in for the Online Charging System an operator runs
 * (Ericsson OCC, Huawei CBS, Matrixx, Totogi...). It owns what an OCS owns:
 * rate plans, subscriber balances, data counters (buckets) and rollover
 * policy. The BSS never pretends to be it — the SOM provisions subscribers
 * here at activation (by the chargingSpecId the catalog references), the
 * TMF654 facade projects balances from here, and the network (simulated by
 * POST /usage) charges against it in real time.
 *
 * Deliberately in-memory: it is a demo seam target, not a product.
 */
'use strict';

const http = require('http');
const { URL } = require('url');

const PORT = process.env.PORT || 8080;

/* The OCS's own reference data: rate plans built in ITS tooling. The catalog
 * references these by id — never contains them. */
const RATE_PLANS = {
  'RG-DATA-2':  { name: '2 GB counter',  gb: 2,   rollover: false },
  'RG-DATA-10': { name: '10 GB counter', gb: 10,  rollover: false },
  'RG-DATA-30': { name: '30 GB counter', gb: 30,  rollover: true },
  'RG-DATA-50': { name: '50 GB counter', gb: 50,  rollover: true },
  'RG-DATA-60': { name: '60 GB counter', gb: 60,  rollover: true },
  'RG-UNL':     { name: 'Unlimited',     gb: 1000, rollover: false },
};

const subscribers = new Map(); // id -> subscriber

function bucketFor(plan, planId) {
  return {
    id: 'bkt-' + Math.random().toString(36).slice(2, 10),
    name: plan.name,
    ratePlanId: planId,
    totalGB: plan.gb,
    usedGB: 0,
    rolloverGB: 0,
    rollover: plan.rollover,
  };
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const send = (code, body) => {
    res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  };
  let raw = '';
  req.on('data', (chunk) => { raw += chunk; });
  req.on('end', () => {
    let body = {};
    try { body = raw ? JSON.parse(raw) : {}; } catch { return send(400, { error: 'bad json' }); }

    // provision a subscriber onto a rate plan (SOM calls this at activation)
    if (req.method === 'POST' && url.pathname === '/subscribers') {
      const plan = RATE_PLANS[body.ratePlanId];
      if (!plan) return send(404, { error: `unknown rate plan '${body.ratePlanId}'` });
      const existing = [...subscribers.values()]
        .find((s) => s.serviceId === body.serviceId && s.tenantId === body.tenantId);
      if (existing) return send(200, existing); // idempotent per service
      const sub = {
        id: 'sub-' + Math.random().toString(36).slice(2, 10),
        tenantId: body.tenantId, partyId: body.partyId, serviceId: body.serviceId,
        ratePlanId: body.ratePlanId, buckets: [bucketFor(plan, body.ratePlanId)],
      };
      subscribers.set(sub.id, sub);
      return send(201, sub);
    }

    if (req.method === 'GET' && url.pathname === '/subscribers') {
      const tenantId = url.searchParams.get('tenantId');
      const partyId = url.searchParams.get('partyId');
      return send(200, [...subscribers.values()].filter((s) =>
        (!tenantId || s.tenantId === tenantId) && (!partyId || s.partyId === partyId)));
    }

    const m = url.pathname.match(/^\/subscribers\/([^/]+)(?:\/(usage|credit|suspend|resume))?$/);
    if (m && subscribers.has(m[1])) {
      const sub = subscribers.get(m[1]);
      // vacation hold: a suspended line charges nothing and passes nothing
      if (req.method === 'POST' && (m[2] === 'suspend' || m[2] === 'resume')) {
        sub.status = m[2] === 'suspend' ? 'suspended' : 'active';
        return send(200, sub);
      }
      // simulated network charging (Gy would do this in production)
      if (req.method === 'POST' && m[2] === 'usage') {
        if (sub.status === 'suspended') return send(409, { error: 'line is suspended' });
        const bucket = sub.buckets[0];
        bucket.usedGB = Number((bucket.usedGB + Number(body.gb || 0)).toFixed(3));
        return send(200, sub);
      }
      // a top-up credits the counter
      if (req.method === 'POST' && m[2] === 'credit') {
        const bucket = sub.buckets[0];
        bucket.totalGB = Number((bucket.totalGB + Number(body.gb || 0)).toFixed(3));
        return send(200, sub);
      }
      // plan change: swap the rate plan, keep earned rollover
      if (req.method === 'PATCH' && !m[2]) {
        const plan = RATE_PLANS[body.ratePlanId];
        if (!plan) return send(404, { error: `unknown rate plan '${body.ratePlanId}'` });
        const carried = sub.buckets[0]?.rolloverGB || 0;
        sub.ratePlanId = body.ratePlanId;
        sub.buckets = [bucketFor(plan, body.ratePlanId)];
        sub.buckets[0].rolloverGB = carried;
        return send(200, sub);
      }
      if (req.method === 'GET' && !m[2]) return send(200, sub);
    }

    // month end: unused data rolls over where the plan allows it
    if (req.method === 'POST' && url.pathname === '/cycle') {
      for (const sub of subscribers.values()) {
        for (const bucket of sub.buckets) {
          bucket.rolloverGB = bucket.rollover
            ? Math.max(0, Number((bucket.totalGB + bucket.rolloverGB - bucket.usedGB).toFixed(3)))
            : 0;
          bucket.usedGB = 0;
        }
      }
      return send(200, { cycled: subscribers.size });
    }

    if (req.method === 'GET' && url.pathname === '/health') return send(200, { status: 'UP' });
    if (req.method === 'GET' && url.pathname === '/ratePlans') return send(200, RATE_PLANS);
    send(404, { error: 'not found' });
  });
});

server.listen(PORT, () => console.log(`mock-ocs listening on ${PORT}`));
