/*
 * Mock market-intelligence feed — stands in for the tariff-comparison
 * subscription an operator buys (Tefficient-style). GET /offers answers
 * the competitor landscape: who sells what data bucket at what price.
 * Deliberately static: the SEAM is the product, the data is a stand-in.
 */
'use strict';
const http = require('http');
const { URL } = require('url');
const PORT = process.env.PORT || 8080;

const OFFERS = [
  { competitor: 'RivalTel', name: 'RivalTel Smart 10', dataGb: 10, price: { unit: 'EUR', value: 11.9 }, notes: 'no binding' },
  { competitor: 'RivalTel', name: 'RivalTel Smart 30', dataGb: 30, price: { unit: 'EUR', value: 24.0 }, notes: 'no binding' },
  { competitor: 'Nordic Mobile', name: 'Nordic Flex 15', dataGb: 15, price: { unit: 'EUR', value: 18.0 }, notes: 'rollover included' },
  { competitor: 'Nordic Mobile', name: 'Nordic Unlimited', dataGb: 999, price: { unit: 'EUR', value: 39.0 }, notes: 'fair use 100GB' },
];

http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const json = (code, body) => {
    res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  };
  if (req.method === 'GET' && url.pathname === '/health') return json(200, { status: 'UP' });
  if (req.method === 'GET' && url.pathname === '/offers') {
    const auth = req.headers.authorization || '';
    if (!auth.startsWith('Bearer ') || auth.length <= 7) return json(401, { error: 'subscription token required' });
    return json(200, OFFERS);
  }
  json(404, { error: 'not found' });
}).listen(PORT, () => console.log(`mock-market listening on ${PORT}`));
