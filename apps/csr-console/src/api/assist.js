// assist: part of the CSR console's TMF client (split from api.js; the barrel re-exports).
import { KNOWLEDGE, authFetch } from './_http.js';

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

/* THE AGENT'S DIAGNOSES, AND THE ONE WORD THAT MAKES THEM COMPOUND.
 *
 * The incident agent diagnoses a failed order flow and writes it onto the
 * ticket as a note — so a human already reads it. What there was no way to do,
 * anywhere in any screen, was say whether it helped. Three "useful" verdicts on
 * one signature draft a runbook; an approved runbook makes every later
 * diagnosis of that signature deterministic and free of a model call.
 *
 * Nothing called these endpoints, so on 12 Oct the ledger held 280 diagnoses,
 * 234 of them unreviewed, and zero approved runbooks. The loop was not broken.
 * It had never been fed. (#285)
 *
 * Fail-soft like every optional component: no intelligence module, no verdict
 * control, and the ticket behaves exactly as it did.
 */
export async function incidentTraces() {
  const res = await authFetch('/ai/v1/incident');
  if (!res.ok) return [];
  return res.json().catch(() => []);
}

export async function judgeIncident(id, useful, note) {
  const res = await authFetch(`/ai/v1/incident/${encodeURIComponent(id)}/verdict`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ useful, note }),
  });
  if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || `HTTP ${res.status}`);
  return res.json().catch(() => ({}));
}
