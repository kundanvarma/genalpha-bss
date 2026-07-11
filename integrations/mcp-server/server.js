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
