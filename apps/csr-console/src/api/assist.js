// assist: part of the CSR console's TMF client (split from api.js; the barrel re-exports).
import { KNOWLEDGE, authFetch, json } from './_http.js';

// Intelligence copilot — fail-soft like every optional component: if the
// module is not deployed, the copilot card simply does not render results.
export async function aiCustomerSummary(payload) {
  const res = await authFetch('/ai/v1/customerSummary', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || `HTTP ${res.status}`);
  return res.json();
}

export async function aiTicketReply(payload) {
  const res = await authFetch('/ai/v1/ticketReply', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || `HTTP ${res.status}`);
  return res.json();
}

/** The library, audience-filtered by the agent's own token. */
export async function searchKnowledge(q) {
  const res = await authFetch(`${KNOWLEDGE}/article${q ? `?q=${encodeURIComponent(q)}` : ''}`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

/** Grounded answer with sources — retrieval runs as the asker. */
/** The shelf for one screen: articles tagged for it, audience-gated by the server. */
export async function shelfKnowledge(tag) {
  const res = await authFetch(`${KNOWLEDGE}/article?tag=${encodeURIComponent(tag)}`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

export async function askKnowledge(question, context) {
  const res = await authFetch('/ai/v1/knowledgeAsk', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question, context }),
  });
  if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || `HTTP ${res.status}`);
  return res.json();
}
