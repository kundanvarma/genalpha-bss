#!/usr/bin/env node
/*
 * genalpha-bss MCP server — the agentic connective tissue of the AI-slice PoC.
 *
 * The Catalyst "Autonomy Accelerated" story hinges on standardised
 * agent-to-agent integration: a sales agent (Claude), the BSS, a vendor OSS
 * and an ITSM platform interoperating as one workflow. This server is the BSS
 * end of that: it exposes the lead-to-order loop as MCP tools over the TM
 * Forum Open APIs, so any MCP-speaking agent can capture intent, run
 * feasibility, price a quote and place the order — the same standardised
 * tissue (MCP / A2A) named by China Mobile's A2A-T, Amdocs and Jio.
 *
 * Auth: the agent acts with a client-credentials token (a machine identity),
 * exactly like every other service-to-service caller in the BSS. No new trust
 * model — the agent is just another tenant-scoped machine principal.
 */
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { CallToolRequestSchema, ListToolsRequestSchema } from '@modelcontextprotocol/sdk/types.js';

const GATEWAY = process.env.BSS_GATEWAY_URL || 'http://localhost:8080';
const TOKEN_URL = process.env.BSS_TOKEN_URL
  || 'http://localhost:8085/realms/bss/protocol/openid-connect/token';
const CLIENT_ID = process.env.BSS_CLIENT_ID || 'bss-demo';
const CLIENT_SECRET = process.env.BSS_CLIENT_SECRET; // set for client_credentials
const USERNAME = process.env.BSS_USERNAME || 'demo';
const PASSWORD = process.env.BSS_PASSWORD || 'demo';
// Retail commerce tools act FOR a shopper, never as the machine: the
// shopper's own credential is exchanged (RFC 8693 / OAuth token exchange)
// through the bss-agent client for a token scoped to commerce alone —
// it can order and pay; it cannot read bills, edit the profile or open
// tickets, which the shopper's own token can.
const SHOPPER_USERNAME = process.env.BSS_SHOPPER_USERNAME || 'paula@family.example';
const SHOPPER_PASSWORD = process.env.BSS_SHOPPER_PASSWORD || 'paula';
const AGENT_CLIENT_ID = process.env.BSS_AGENT_CLIENT_ID || 'bss-agent';
const AGENT_CLIENT_SECRET = process.env.BSS_AGENT_CLIENT_SECRET || 'agent-secret';
// The digital-worker BADGE: a per-tenant staff identity the operator mints
// (and can revoke) on the console. No badge, no workforce — the badge IS
// the opt-in. Set these when deploying a worker (e.g. the Hermes package).
const WORKER_USERNAME = process.env.BSS_WORKER_USERNAME;
const WORKER_PASSWORD = process.env.BSS_WORKER_PASSWORD;

let cached = null;
async function token() {
  if (cached && cached.expiresAt > Date.now() + 30000) return cached.value;
  const form = CLIENT_SECRET
    ? { grant_type: 'client_credentials', client_id: CLIENT_ID, client_secret: CLIENT_SECRET }
    : { grant_type: 'password', client_id: CLIENT_ID, username: USERNAME, password: PASSWORD };
  const res = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(form),
  });
  if (!res.ok) throw new Error(`token: ${res.status}`);
  const body = await res.json();
  cached = { value: body.access_token, expiresAt: Date.now() + body.expires_in * 1000 };
  return cached.value;
}

/**
 * The delegated commerce token: password-grant the shopper (dev stand-in for
 * their real session), then EXCHANGE it via the confidential bss-agent
 * client. Keycloak intersects the shopper's roles with the client's commerce
 * scope, so what comes back can order and pay — nothing else.
 */
let cachedCommerce = null;
async function commerceToken() {
  if (cachedCommerce && cachedCommerce.expiresAt > Date.now() + 30000) return cachedCommerce.value;
  const post = async (form) => {
    const res = await fetch(TOKEN_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams(form),
    });
    if (!res.ok) throw new Error(`token: ${res.status} ${await res.text()}`);
    return res.json();
  };
  const shopper = await post({
    grant_type: 'password', client_id: CLIENT_ID,
    username: SHOPPER_USERNAME, password: SHOPPER_PASSWORD,
  });
  const exchanged = await post({
    grant_type: 'urn:ietf:params:oauth:grant-type:token-exchange',
    client_id: AGENT_CLIENT_ID,
    client_secret: AGENT_CLIENT_SECRET,
    subject_token: shopper.access_token,
    subject_token_type: 'urn:ietf:params:oauth:token-type:access_token',
    requested_token_type: 'urn:ietf:params:oauth:token-type:access_token',
  });
  cachedCommerce = {
    value: exchanged.access_token,
    expiresAt: Date.now() + exchanged.expires_in * 1000,
  };
  return cachedCommerce.value;
}

/** The worker's badge token — a password grant for the minted staff
 * identity. Refuses loudly when no badge is configured: employment is the
 * operator's explicit act, never a default. */
let cachedWorker = null;
async function workerToken() {
  if (!WORKER_USERNAME || !WORKER_PASSWORD) {
    throw new Error('no digital-worker badge configured (BSS_WORKER_USERNAME/BSS_WORKER_PASSWORD)'
      + ' — the operator mints one on the console (Staff → grant digital-worker)');
  }
  if (cachedWorker && cachedWorker.expiresAt > Date.now() + 30000) return cachedWorker.value;
  const res = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'password', client_id: CLIENT_ID,
      username: WORKER_USERNAME, password: WORKER_PASSWORD,
    }),
  });
  if (!res.ok) throw new Error(`worker badge token: ${res.status} ${await res.text()}`);
  const body = await res.json();
  cachedWorker = { value: body.access_token, expiresAt: Date.now() + body.expires_in * 1000 };
  return cachedWorker.value;
}

async function workerApi(method, path, payload) {
  const res = await fetch(`${GATEWAY}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${await workerToken()}`,
      ...(payload ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(payload ? { body: JSON.stringify(payload) } : {}),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status}: ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : {};
}

/** Anonymous call — the ACP feed and open checkout sessions are public,
 * exactly as public as the storefront pages they mirror. */
async function anonApi(method, path, payload, headers = {}) {
  const res = await fetch(`${GATEWAY}${path}`, {
    method,
    headers: { ...(payload ? { 'Content-Type': 'application/json' } : {}), ...headers },
    ...(payload ? { body: JSON.stringify(payload) } : {}),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status}: ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : {};
}

async function api(method, path, payload) {
  const res = await fetch(`${GATEWAY}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${await token()}`,
      ...(payload ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(payload ? { body: JSON.stringify(payload) } : {}),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status}: ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : {};
}

const TOOLS = [
  {
    name: 'draft_intent',
    description: 'Turn a plain-language B2B sales ask (e.g. "a 5G slice with AI glasses '
      + 'for the stadium-north tournament") into a structured TMF921 network intent expression.',
    inputSchema: {
      type: 'object',
      properties: { ask: { type: 'string', description: 'The business need in plain language' } },
      required: ['ask'],
    },
    run: (a) => api('POST', '/ai/v1/intentDraft', { ask: a.ask }),
  },
  {
    name: 'submit_intent',
    description: 'Submit a network intent to the OSS; it runs an autonomous feasibility check and '
      + 'returns a proposal (which may upsell edge AI inferencing when GPU capacity exists at the edge).',
    inputSchema: {
      type: 'object',
      properties: {
        name: { type: 'string' },
        customerPartyId: { type: 'string' },
        place: { type: 'string' },
        latencyMs: { type: 'number' },
        bandwidthMbps: { type: 'number' },
        aiTokensMillions: { type: 'number' },
      },
      required: ['name', 'place', 'latencyMs'],
    },
    run: (a) => api('POST', '/tmf-api/intentManagement/v4/intent', {
      name: a.name,
      relatedParty: a.customerPartyId ? [{ id: a.customerPartyId, role: 'customer' }] : undefined,
      expression: {
        place: a.place, latencyMs: a.latencyMs,
        bandwidthMbps: a.bandwidthMbps, aiTokensMillions: a.aiTokensMillions,
      },
    }),
  },
  {
    name: 'create_quote',
    description: 'Create a priced TMF648 quote from a feasibility-checked intent. Line items carry '
      + 'catalog prices and token allowances; includes an AI-drafted narrative.',
    inputSchema: {
      type: 'object',
      properties: { intentId: { type: 'string' } },
      required: ['intentId'],
    },
    run: (a) => api('POST', '/tmf-api/quoteManagement/v4/quote', { intentId: a.intentId }),
  },
  {
    name: 'accept_quote',
    description: 'Approve and accept a quote — this places the product order and provisions the slice.',
    inputSchema: {
      type: 'object',
      properties: { quoteId: { type: 'string' } },
      required: ['quoteId'],
    },
    run: async (a) => {
      await api('PATCH', `/tmf-api/quoteManagement/v4/quote/${a.quoteId}`, { state: 'approved' });
      return api('POST', `/tmf-api/quoteManagement/v4/quote/${a.quoteId}/accept`);
    },
  },
  {
    name: 'list_quotes',
    description: 'List the current tenant\'s quotes and their states.',
    inputSchema: { type: 'object', properties: {} },
    run: () => api('GET', '/tmf-api/quoteManagement/v4/quote'),
  },
  /* ---- retail agentic commerce: the ACP surface worn as MCP tools ---- */
  {
    name: 'search_offerings',
    description: 'Search the operator\'s retail catalog (phones, plans, accessories, bundles) via '
      + 'the ACP product feed. Returns priced, in-stock offerings an agent can buy.',
    inputSchema: {
      type: 'object',
      properties: { query: { type: 'string', description: 'Free-text filter on title/description/category' } },
    },
    run: async (a) => {
      const feed = await anonApi('GET', '/acp/product_feed');
      const q = (a.query || '').toLowerCase();
      const hits = feed.products.filter((p) => !q
        || `${p.title} ${p.description || ''} ${p.item_category || ''}`.toLowerCase().includes(q));
      return hits.slice(0, 10);
    },
  },
  {
    name: 'get_offering',
    description: 'One offering in detail, plus "customers who bought this also bought" — the '
      + 'operator\'s real co-purchase signal, aggregate only.',
    inputSchema: {
      type: 'object',
      properties: { offeringId: { type: 'string' } },
      required: ['offeringId'],
    },
    run: async (a) => {
      const feed = await anonApi('GET', `/acp/product_feed?id=${encodeURIComponent(a.offeringId)}`);
      if (!feed.products.length) throw new Error(`offering ${a.offeringId} is not in the feed`);
      const alsoBought = await anonApi('GET',
        `/tmf-api/recommendationManagement/v4/affinity?forOfferingId=${encodeURIComponent(a.offeringId)}`)
        .catch(() => []);
      return { ...feed.products[0], also_bought: alsoBought };
    },
  },
  {
    name: 'start_checkout',
    description: 'Open an ACP checkout session from items [{id, quantity}]. Returns the session '
      + 'with honest totals: one-time charges due now; recurring prices bill on the first invoice.',
    inputSchema: {
      type: 'object',
      properties: {
        items: {
          type: 'array',
          items: {
            type: 'object',
            properties: { id: { type: 'string' }, quantity: { type: 'number' } },
            required: ['id'],
          },
        },
      },
      required: ['items'],
    },
    run: (a) => anonApi('POST', '/acp/checkout_sessions', { items: a.items }),
  },
  {
    name: 'update_checkout',
    description: 'Change an open checkout session\'s items; totals are re-priced from the feed.',
    inputSchema: {
      type: 'object',
      properties: {
        sessionId: { type: 'string' },
        items: {
          type: 'array',
          items: {
            type: 'object',
            properties: { id: { type: 'string' }, quantity: { type: 'number' } },
            required: ['id'],
          },
        },
      },
      required: ['sessionId', 'items'],
    },
    run: (a) => anonApi('POST', `/acp/checkout_sessions/${a.sessionId}`, { items: a.items }),
  },
  {
    name: 'complete_checkout',
    description: 'Complete the checkout: charge the delegated payment token for the due-now total '
      + 'and place the real product order — under a token-exchange credential scoped to commerce '
      + 'alone (it can order and pay; it cannot touch the rest of the customer\'s account). '
      + 'Idempotent: retrying with the same session returns the same order.',
    inputSchema: {
      type: 'object',
      properties: {
        sessionId: { type: 'string' },
        paymentToken: { type: 'string', description: 'The delegated payment token (ACP SharedPaymentToken)' },
      },
      required: ['sessionId', 'paymentToken'],
    },
    run: async (a) => anonApi('POST', `/acp/checkout_sessions/${a.sessionId}/complete`,
      { payment_data: { token: a.paymentToken } },
      {
        Authorization: `Bearer ${await commerceToken()}`,
        'Idempotency-Key': `mcp-${a.sessionId}`,
      }),
  },
  /* ---- the digital workforce: back-office & care, on the worker's badge ---- */
  {
    name: 'workforce_list_tasks',
    description: 'The open work queue for this operator: unassigned trouble tickets and unapplied '
      + 'payments, derived live from the real backlogs. Requires the digital-worker badge.',
    inputSchema: { type: 'object', properties: {} },
    run: () => workerApi('GET', '/ai/v1/workforce/tasks'),
  },
  {
    name: 'workforce_claim',
    description: 'Claim a task from the queue (a 15-minute lease — a crashed worker\'s task frees '
      + 'itself). Two workers can never hold the same task.',
    inputSchema: {
      type: 'object',
      properties: { taskId: { type: 'string' } },
      required: ['taskId'],
    },
    run: (a) => workerApi('POST', `/ai/v1/workforce/tasks/${encodeURIComponent(a.taskId)}/claim`),
  },
  {
    name: 'workforce_complete',
    description: 'Complete a claimed task. Completion is VERIFIED: a ticket task completes only '
      + 'when the ticket is actually resolved; a cash task only when the payment left the '
      + 'worklist. Optionally self-report model usage for the operator\'s dashboard.',
    inputSchema: {
      type: 'object',
      properties: {
        taskId: { type: 'string' },
        outcome: { type: 'string', description: 'One sentence: what was done' },
        selfReported: {
          type: 'object',
          properties: {
            tokens: { type: 'number' }, costMicros: { type: 'number' }, model: { type: 'string' },
          },
        },
      },
      required: ['taskId', 'outcome'],
    },
    run: (a) => workerApi('POST', `/ai/v1/workforce/tasks/${encodeURIComponent(a.taskId)}/complete`,
      { outcome: a.outcome, selfReported: a.selfReported }),
  },
  {
    name: 'workforce_escalate',
    description: 'Hand a claimed task to a human, with the reason. Escalating honestly beats '
      + 'completing wrongly — escalations are counted, not punished.',
    inputSchema: {
      type: 'object',
      properties: { taskId: { type: 'string' }, reason: { type: 'string' } },
      required: ['taskId', 'reason'],
    },
    run: (a) => workerApi('POST', `/ai/v1/workforce/tasks/${encodeURIComponent(a.taskId)}/escalate`,
      { reason: a.reason }),
  },
  {
    name: 'care_ticket_get',
    description: 'Read one trouble ticket in full (TMF621) — the context before the work.',
    inputSchema: {
      type: 'object',
      properties: { ticketId: { type: 'string' } },
      required: ['ticketId'],
    },
    run: (a) => workerApi('GET', `/tmf-api/troubleTicket/v4/troubleTicket/${a.ticketId}`),
  },
  {
    name: 'care_ticket_resolve',
    description: 'Resolve a trouble ticket with a note explaining the fix — the same TMF621 '
      + 'transition a human CSR makes, attributed to the worker\'s badge.',
    inputSchema: {
      type: 'object',
      properties: { ticketId: { type: 'string' }, note: { type: 'string' } },
      required: ['ticketId', 'note'],
    },
    run: (a) => workerApi('PATCH', `/tmf-api/troubleTicket/v4/troubleTicket/${a.ticketId}`,
      { status: 'resolved', note: [{ text: a.note }] }),
  },
  {
    name: 'backoffice_unapplied_cash',
    description: 'The AR worklist: bank payments no bill cleanly claims, each with its reason.',
    inputSchema: { type: 'object', properties: {} },
    run: () => workerApi('GET', '/tmf-api/customerBillManagement/v4/remittance/unapplied'),
  },
  {
    name: 'backoffice_list_bills',
    description: 'Open customer bills (TMF678) — to find the bill an unapplied payment belongs to.',
    inputSchema: {
      type: 'object',
      properties: { state: { type: 'string', description: 'Filter, e.g. "new" for open bills' } },
    },
    run: (a) => workerApi('GET', `/tmf-api/customerBillManagement/v4/customerBill?limit=100${a.state ? `&state=${encodeURIComponent(a.state)}` : ''}`),
  },
  {
    name: 'backoffice_apply_payment',
    description: 'Resolve ONE unapplied payment to the bill it belongs to. Money stays honest: '
      + 'the bill must be open and the amount must match to the cent — a mismatch is refused, '
      + 'never forced.',
    inputSchema: {
      type: 'object',
      properties: { unappliedId: { type: 'string' }, billId: { type: 'string' } },
      required: ['unappliedId', 'billId'],
    },
    run: (a) => workerApi('POST',
      `/tmf-api/customerBillManagement/v4/remittance/unapplied/${a.unappliedId}/apply`,
      { billId: a.billId }),
  },
];

const server = new Server(
  { name: 'genalpha-bss', version: '1.0.0' },
  { capabilities: { tools: {} } });

server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: TOOLS.map(({ name, description, inputSchema }) => ({ name, description, inputSchema })),
}));

server.setRequestHandler(CallToolRequestSchema, async (req) => {
  const tool = TOOLS.find((t) => t.name === req.params.name);
  if (!tool) throw new Error(`unknown tool: ${req.params.name}`);
  try {
    const result = await tool.run(req.params.arguments || {});
    return { content: [{ type: 'text', text: JSON.stringify(result, null, 2) }] };
  } catch (e) {
    return { content: [{ type: 'text', text: `Error: ${e.message}` }], isError: true };
  }
});

const transport = new StdioServerTransport();
await server.connect(transport);
console.error('genalpha-bss MCP server ready on stdio');
