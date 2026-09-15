import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { hasRole } from '../auth.js';
import { shelfKnowledge, searchKnowledge, sendMessage, logInteraction, recommendationOutcome, aiWrapUp } from '../api.js';
import { desk } from '../desk.js';

/* GenAlpha Assist — the persistent panel beside the customer. Grounded, never
 * free: the situation and the recommended actions come from the operational
 * ontology (every action dry-run through the registry, its conditions as the
 * "why"); the summary comes from the copilot over the same 360 data; the
 * knowledge comes from the shelf. Accept executes with the agent's own token;
 * dismiss and the thumbs go back to the ontology's decision log, where the
 * ranking reads them next time (the loop). Refreshes when the customer changes
 * or an action ran — never on a keystroke. The wrap-up drafts the after-call
 * note from what the timeline shows happened since the page opened. */

const VERDICT = { holds: '✓', fails: '✗', unknown: '?' };

// what the situation asks the shelf for — the customer-facing article first, the search only as the net
const SITUATION_QUERIES = { incident: ['outage', 'no internet'], bill: ['understanding your bill', 'bill'], paused: ['paused line', 'pause'] };
// what to say, in the agent's own voice, per situation — the article is what the customer gets
const SCRIPTS = {
  explainIncident: "There is a known problem on your line right now. Our network team is already on it and you do not need to do anything at your end. I can send you a note the moment it is cleared.",
  explainBill: "Let me go through your bill line by line: your plan, any one-off charges, and anything outside the plan. If something looks wrong I can open a dispute for you, and collection pauses while we check it.",
};
const isManual = (a) => /operator'?s manual/i.test(a.category || '') || /^Manual · \d+ · /.test(a.title || '');
const sendable = (a) => ['customer', 'all'].includes(a.audience) && !isManual(a);
const readable = (a) => a.audience === 'csr' && !isManual(a);
const cleanTitle = (t) => String(t || '').replace(/^Manual · \d+ · /, '');

export default function Assist({ id, customer, bss, version, act, nbo, setNbo, aiNextBestOffer, sendOffer, orderForCustomer,
  copilot, summarize, onKnowledgeSearch, interactions = [], openTickets = [], openedAt }) {
  const [recs, setRecs] = useState(null);      // null = loading, {…} = answer
  const [error, setError] = useState(null);
  const [open, setOpen] = useState(() => { try { return sessionStorage.getItem('bss.csr.assist') !== 'closed'; } catch { return true; } });
  const [dismissed, setDismissed] = useState({});
  const [done, setDone] = useState(null);
  const [knowledge, setKnowledge] = useState({ send: [], read: [] });
  const [script, setScript] = useState(null);
  const [sent, setSent] = useState(null);
  const [preview, setPreview] = useState(null);
  const [verdicts, setVerdicts] = useState({}); // decisionId -> helpful | unhelpful
  const [wrap, setWrap] = useState(null); // null | 'loading' | {note, disposition, followUp, provider, model} | 'logged'
  const [wrapText, setWrapText] = useState('');

  // a new customer resets the panel; a refresh after an action keeps what was just done on screen
  useEffect(() => { setDone(null); setDismissed({}); setVerdicts({}); setWrap(null); setWrapText(''); setScript(null); }, [id]);
  useEffect(() => {
    let alive = true;
    setRecs(null); setError(null);
    bss.recommendations(id).then((r) => { if (alive) setRecs(r); }).catch((e) => { if (alive) { setRecs({ recommendations: [], situation: [], said: '' }); setError(e.message); } });
    return () => { alive = false; };
  }, [id, version]);

  // contextual knowledge: the customer shelf, plus what the situation asks for
  useEffect(() => {
    if (!recs) return;
    const kinds = [...new Set((recs.situation || []).map((s) => s.kind))];
    const queries = kinds.flatMap((k) => SITUATION_QUERIES[k] || []);
    Promise.all([...queries.map((q) => searchKnowledge(q).catch(() => [])), shelfKnowledge('csr:customers').catch(() => [])])
      .then((lists) => {
        const seen = new Set(); const send = []; const read = [];
        for (const a of lists.flat()) {
          if (!a || seen.has(a.id)) continue;
          seen.add(a.id);
          if (sendable(a) && send.length < 2) send.push(a);
          else if (readable(a) && read.length < 2) read.push(a);
        }
        setKnowledge({ send, read });
      });
  }, [recs]);

  const toggle = () => { const next = !open; setOpen(next); try { sessionStorage.setItem('bss.csr.assist', next ? 'open' : 'closed'); } catch { /* fine */ } };
  const keyOf = (r) => r.action + (r.inputs ? JSON.stringify(r.inputs) : '');
  const live = (recs?.recommendations || []).filter((r) => !dismissed[keyOf(r)]);
  const top = live[0];
  const rest = live.slice(1, 4);
  const tell = (r, outcome, reason) => { if (r.decisionId) recommendationOutcome(r.decisionId, outcome, reason).catch(() => {}); };

  const run = (r) => act(async () => {
    const result = await bss[r.action](r.inputs);
    setDone(result.said);
    desk('suggestion.accept', 'assist:' + r.action, { customer: id });
    tell(r, 'accepted');
    await logInteraction({
      description: `Assist: ${result.said}`,
      channel: 'phone', direction: 'outbound', sourceSystem: 'csr-console',
      relatedParty: [{ id, role: 'customer', '@referredType': 'Individual' }],
    });
  });
  const dismiss = (r) => {
    setDismissed((d) => ({ ...d, [keyOf(r)]: true }));
    desk('suggestion.dismiss', 'assist:' + r.action, { customer: id });
    tell(r, 'dismissed');
  };
  const verdict = (r, v) => {
    setVerdicts((x) => ({ ...x, [r.decisionId]: v }));
    desk('suggestion.feedback', 'assist:' + r.action, { customer: id, verdict: v });
    tell(r, v);
  };
  const explainIt = (r) => {
    setScript(SCRIPTS[r.action] || r.why);
    onKnowledgeSearch?.(r.action === 'explainIncident' ? 'outage' : 'bill');
    desk('suggestion.accept', 'assist:' + r.action, { customer: id });
    tell(r, 'accepted');
  };

  // the after-call note: only what the record shows since the page opened
  const sinceOpen = interactions.filter((ix) => !openedAt || !ix.interactionDate || ix.interactionDate >= openedAt);
  const wrapUp = async () => {
    setWrap('loading');
    try {
      const r = await aiWrapUp({
        customerName: customer ? `${customer.givenName} ${customer.familyName}` : '',
        situation: (recs?.situation || []).map((s) => s.says),
        actions: sinceOpen.map((ix) => ({ description: ix.description, direction: ix.direction, at: ix.interactionDate })),
        openTickets: openTickets.map((t) => ({ name: t.name, status: t.status })),
        recommendationTaken: done,
      });
      setWrap(r); setWrapText(r.note + (r.followUp ? ` Follow-up: ${r.followUp}` : ''));
      desk('wrapup.drafted', 'assist:wrapup', { actions: sinceOpen.length });
    } catch (e) { setWrap(null); setError(e.message); }
  };
  const logWrap = () => act(async () => {
    await logInteraction({
      description: `Wrap-up (${wrap?.disposition || 'note'}): ${wrapText.trim()}`,
      channel: 'phone', direction: 'inbound', sourceSystem: 'csr-console',
      relatedParty: [{ id, role: 'customer', '@referredType': 'Individual' }],
    });
    desk('wrapup.logged', 'assist:wrapup', { disposition: wrap?.disposition });
    setWrap('logged');
  });

  const feedback = (r) => (
    <div className="assist-feedback" data-testid="assist-feedback">
      <span className="dim small">Was this the right call?</span>
      {verdicts[r.decisionId] ? <span className="ok small" data-testid="assist-feedback-done">Thanks — noted as {verdicts[r.decisionId]}.</span> : (
        <>
          <button className="ghost small" data-testid="assist-helpful" onClick={() => verdict(r, 'helpful')}>👍 Yes</button>
          <button className="ghost small" data-testid="assist-unhelpful" onClick={() => verdict(r, 'unhelpful')}>👎 No</button>
        </>
      )}
    </div>
  );

  return (
    <aside className={`assist ${open ? 'open' : 'closed'}`} data-testid="assist-panel" aria-label="GenAlpha Assist">
      <div className="assist-head">
        <strong>GenAlpha Assist</strong>
        <button className="ghost small" onClick={toggle} aria-expanded={open} data-testid="assist-toggle">{open ? 'Hide' : 'Show'}</button>
      </div>
      {open && (
        <>
          {/* situation */}
          <div className="assist-block" data-testid="assist-situation">
            <div className="assist-label">Situation</div>
            {recs === null && <p className="dim small">Reading the customer…</p>}
            {recs && !(recs.situation || []).length && <p className="dim small">Nothing open on this customer: no incident on their lines, no paused line, no open bill.</p>}
            {(recs?.summary || recs?.situation || []).map((s, i) => (
              <p key={s.kind + (s.id || i)} className={s.severity === 'critical' ? 'error' : s.kind === 'incident' ? 'error' : 'small'} data-testid={`assist-situation-${s.kind}`}>
                {s.kind === 'incident' ? '⚠ ' : s.kind === 'overdue' ? '🔴 ' : s.kind === 'arranged' ? '🤝 ' : s.kind === 'bill' || s.kind === 'disputed' ? '💳 ' : s.kind === 'paused' ? '⏸ ' : ''}{s.says}
                {s.actionRequired === false && s.severity === 'info' ? <span className="dim"> — no action needed</span> : null}
              </p>
            ))}
            {error && <p className="dim small">Assist could not read the ontology ({error}).</p>}
          </div>

          {/* recommended action — a governed action with its conditions, or a thing to explain */}
          <div className="assist-block" data-testid="assist-recommendation">
            <div className="assist-label">Recommended action</div>
            {recs && !top && !done && <p className="dim small">{recs.said || 'Nothing stands out. Listen first.'}</p>}
            {done && <p className="ok small" data-testid="assist-done">{done}</p>}
            {top && (
              <div className="assist-rec" data-testid={`assist-rec-${top.action}`}>
                <strong>{top.title}</strong>
                <p className="small">{top.why}</p>
                {top.ranking && <p className="assist-ranking" data-testid="assist-ranking">Ranked here because: {top.ranking.says}.</p>}
                {top.check && (
                  <details className="small">
                    <summary className="dim">Why: {top.check.preconditions.filter((v) => v.verdict === 'holds').length} of {top.check.preconditions.length} conditions hold</summary>
                    {top.check.preconditions.map((v) => <p key={v.id} className="dim small">{VERDICT[v.verdict] || '?'} {v.says}{v.detail ? ` — ${v.detail}` : ''}</p>)}
                    <p className="dim small">Permission: {top.check.permission?.says}. Policy: {top.check.policy?.says}.</p>
                  </details>
                )}
                <div className="stack">
                  {top.kind === 'action' && top.allowed && (
                    <button className="primary" data-testid="assist-do" onClick={() => run(top)}>Do it — {top.title.toLowerCase()}</button>
                  )}
                  {top.kind === 'action' && !top.allowed && <span className="dim small">Cannot run now — see why above.</span>}
                  {top.kind === 'explain' && (
                    <button className="primary" data-testid="assist-explain" onClick={() => explainIt(top)}>What to say</button>
                  )}
                  {script && <blockquote className="assist-script small" data-testid="assist-script">"{script}"</blockquote>}
                  <button className="ghost" data-testid="assist-dismiss" onClick={() => dismiss(top)}>Not relevant</button>
                </div>
                {feedback(top)}
              </div>
            )}
            {rest.length > 0 && (
              <ul className="small assist-more">
                {rest.map((r) => <li key={keyOf(r)}>{r.title} <span className="dim">— {r.why}</span></li>)}
              </ul>
            )}
          </div>

          {/* the copilot's summary of the same 360 — the model phrases, the data decides */}
          <section className="assist-block copilot" data-testid="copilot-card">
            <div className="assist-label">Summary</div>
            {!copilot && hasRole('ai:use') && (
              <button className="ghost" data-testid="copilot-summarize" onClick={summarize}>
                ✨ Summarize this customer
              </button>
            )}
            {copilot === 'loading' && <p className="dim small">Copilot is reading the 360…</p>}
            {copilot && copilot !== 'loading' && (
              <>
                <p data-testid="copilot-summary">{copilot.summary}</p>
                <ul className="small">
                  {copilot.nextActions.map((a, i) => <li key={i}>{a}</li>)}
                </ul>
                <p className="dim small">Drafted by {copilot.provider} ({copilot.model}) — verify before acting.</p>
              </>
            )}
          </section>

          {/* an offer to consider — only ever one of the actions, never the first */}
          <section className="assist-block copilot" data-testid="nbo-card">
            <div className="assist-label">Offer to consider</div>
            {!nbo && hasRole('ai:use') && (
              <button className="ghost" data-testid="nbo-ask" onClick={async () => {
                setNbo('loading');
                try { setNbo(await aiNextBestOffer(id)); } catch (e) { setNbo({ reason: e.message }); }
              }}>
                🎯 Weigh the shelf
              </button>
            )}
            {nbo === 'loading' && <p className="dim small">Weighing the shelf against this customer…</p>}
            {nbo && nbo !== 'loading' && (
              <p data-testid="nbo-answer">
                {nbo.offer ? <strong>{nbo.offer.name}</strong> : null} <span className="dim">{nbo.reason}</span>
                {nbo.offer && (
                  <>
                    {' '}
                    <button className="ghost" data-testid="nbo-send" onClick={() => act(() => sendOffer(id, nbo.offer, 'Your agent'))}>Send offer</button>
                    {hasRole('ordering:write') && (
                      <button className="ghost" data-testid="nbo-order" onClick={() => act(() => orderForCustomer(id, nbo.offer))}>Order now</button>
                    )}
                  </>
                )}
              </p>
            )}
          </section>

          {/* contextual knowledge: the shelf, pulled by the situation */}
          <div className="assist-block" data-testid="assist-knowledge">
            <div className="assist-label">Send the customer</div>
            {!knowledge.send.length && <p className="dim small">No customer article for this situation yet — write one under Knowledge and it appears here.</p>}
            {knowledge.send.map((a) => (
              <div key={a.id} className="assist-article" data-testid={`assist-article-${a.id}`}>
                <div className="row" style={{ padding: '6px 0' }}>
                  <span>{cleanTitle(a.title)}</span>
                  <div className="rowend">
                    <button className="ghost small" onClick={() => setPreview(preview === a.id ? null : a.id)}>{preview === a.id ? 'Close' : 'Preview'}</button>
                    <button className="ghost small" data-testid={`assist-send-${a.id}`} title="Send this article to the customer's inbox"
                      onClick={() => act(async () => {
                        await sendMessage(id, cleanTitle(a.title), a.body);
                        setSent(a.id);
                        await logInteraction({ description: `Article sent to the customer: ${cleanTitle(a.title)}`, channel: 'phone', direction: 'outbound', sourceSystem: 'csr-console',
                          relatedParty: [{ id, role: 'customer', '@referredType': 'Individual' }] });
                      })}>{sent === a.id ? 'Sent ✓' : 'Send'}</button>
                  </div>
                </div>
                {preview === a.id && <p className="small assist-preview">{a.body}</p>}
              </div>
            ))}
            {knowledge.read.length > 0 && (
              <>
                <div className="assist-label">For you to read</div>
                {knowledge.read.map((a) => (
                  <div key={a.id} className="assist-article" data-testid={`assist-read-${a.id}`}>
                    <div className="row" style={{ padding: '6px 0' }}>
                      <span>{cleanTitle(a.title)}</span>
                      <button className="ghost small" onClick={() => setPreview(preview === a.id ? null : a.id)}>{preview === a.id ? 'Close' : 'Read'}</button>
                    </div>
                    {preview === a.id && <p className="small assist-preview">{a.body}</p>}
                  </div>
                ))}
              </>
            )}
            <Link className="dim small" to="/knowledge">Open the knowledge base →</Link>
          </div>

          {/* after-call work: the note drafted from what the record shows, logged by the agent */}
          {hasRole('ai:use') && (
            <div className="assist-block wrapup" data-testid="assist-wrapup">
              <div className="assist-label">Wrap up</div>
              {!wrap && (
                <>
                  <p className="dim small">{sinceOpen.length ? `${sinceOpen.length} thing${sinceOpen.length === 1 ? '' : 's'} logged since this page opened.` : 'Nothing logged since this page opened yet.'}</p>
                  <button className="ghost" data-testid="assist-wrapup-draft" onClick={wrapUp}>📝 Draft the after-call note</button>
                </>
              )}
              {wrap === 'loading' && <p className="dim small">Writing the note from the record…</p>}
              {wrap && wrap !== 'loading' && wrap !== 'logged' && (
                <>
                  <textarea data-testid="assist-wrapup-text" aria-label="After-call note" value={wrapText} onChange={(e) => setWrapText(e.target.value)} />
                  <p className="dim small">Disposition: {wrap.disposition}. Drafted by {wrap.provider} ({wrap.model}) — edit before logging.</p>
                  <div className="stack">
                    <button className="primary" data-testid="assist-wrapup-log" onClick={logWrap} disabled={!wrapText.trim()}>Log the note</button>
                    <button className="ghost" onClick={() => setWrap(null)}>Discard</button>
                  </div>
                </>
              )}
              {wrap === 'logged' && <p className="ok small" data-testid="assist-wrapup-done">Wrap-up logged on the timeline.</p>}
            </div>
          )}
          {customer?.id && <p className="dim small assist-foot">Grounded on the ontology's reading of this customer with your own rights; unanswered: {(recs?.unanswered || []).length ? recs.unanswered.join(', ') : 'nothing'}.</p>}
        </>
      )}
    </aside>
  );
}
