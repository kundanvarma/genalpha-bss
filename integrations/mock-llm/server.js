/*
 * Mock OpenAI-compatible LLM — proves TIERED ROUTING: it answers every
 * chat completion with content that NAMES THE MODEL it was asked for,
 * and keeps a ledger (GET /requests) of which models served which kind
 * of prompt. Contract-aware just enough that the callers stay happy:
 * a product-copilot prompt gets a JSON answer, a copywriting prompt
 * gets the SUBJECT/BODY shape.
 */
'use strict';
const http = require('http');
const { URL } = require('url');
const PORT = process.env.PORT || 8080;
const requests = [];

http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const json = (code, body) => {
    res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  };
  if (req.method === 'GET' && url.pathname === '/health') return json(200, { status: 'UP' });
  if (req.method === 'GET' && url.pathname === '/requests') return json(200, requests);
  if (req.method === 'POST' && url.pathname === '/v1/messages') {
    // the ANTHROPIC dialect — a different provider protocol on the same box
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      try {
        const chat = JSON.parse(body || '{}');
        const model = chat.model || 'unknown';
        const system = String(chat.system || '');
        // the KEY it was called with: the model door (#283) must present the tenant's
        // provider key, never the worker's own door key
        // and an excerpt of the conversation it was SENT — the model door redacts customer
        // data before it leaves, and a suite proves it here, on the provider's side
        const said = (chat.messages || []).map((m) => (typeof m.content === 'string' ? m.content
          : (m.content || []).map((b) => b.text || (typeof b.content === 'string' ? b.content : '')).join(' '))).join(' | ');
        requests.push({ model, api: 'anthropic', task: system.slice(0, 60), key: req.headers['x-api-key'] || null,
          said: said.slice(0, 400), at: new Date().toISOString() });
        let text;
        if (system.includes('product copilot')) {
          text = JSON.stringify({ kind: 'question',
            message: `[${model}] A streaming service fits Partner services — what should it cost per month?`,
            proposal: null });
        } else {
          text = `SUBJECT: [${model}] An offer picked for you\nBODY: [${model}] Answered over the anthropic dialect.`;
        }
        // usage, as a real provider reports it — deterministic (a quarter of the bytes),
        // so a suite can check the ledger recorded exactly what the provider said
        const usage = { input_tokens: Math.ceil(body.length / 4), output_tokens: Math.ceil(text.length / 4),
          cache_creation_input_tokens: 0, cache_read_input_tokens: 0 };
        json(200, { id: `msg_mock_${requests.length}`, type: 'message', role: 'assistant', model,
          content: [{ type: 'text', text }], stop_reason: 'end_turn', usage });
      } catch {
        json(400, { error: 'unparseable' });
      }
    });
    return;
  }

  if (req.method === 'POST' && url.pathname === '/v1/chat/completions') {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      try {
        const chat = JSON.parse(body || '{}');
        const model = chat.model || 'unknown';
        const system = (chat.messages || []).find((m) => m.role === 'system')?.content || '';
        requests.push({ model, api: 'openai', task: system.slice(0, 60), at: new Date().toISOString() });
        let content;
        if (system.includes('product copilot')) {
          content = JSON.stringify({ kind: 'question',
            message: `[${model}] A streaming service fits Partner services — what should it cost per month?`,
            proposal: null });
        } else {
          content = `SUBJECT: [${model}] An offer picked for you\nBODY: [${model}] This tier-routed`
            + ' answer came from the model the task deserved.';
        }
        json(200, { choices: [{ message: { role: 'assistant', content } }] });
      } catch {
        json(400, { error: 'unparseable' });
      }
    });
    return;
  }
  json(404, { error: 'not found' });
}).listen(PORT, () => console.log(`mock-llm listening on ${PORT}`));
