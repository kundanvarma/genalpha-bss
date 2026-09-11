import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { authFetch, hasRole } from '../auth.js';
import { aiChatIntent } from '../api.js';
import { desk } from '../desk.js';

const BASE = '/ai/v1/careChat/agent';

/**
 * The live-chat desk: every open care-chat conversation (guest and customer),
 * bot transcript included. The moment an agent replies, the session flips to
 * 'agent' and the bot goes silent — from then on it's human-to-human, and the
 * customer's widget shows the replies live.
 *
 * Live intent: each time the customer adds a message, the AI seam reads the
 * transcript and says what they want (a chip with its confidence) and offers a
 * reply to consider. The agent uses it, edits it, or ignores it; nothing is
 * sent by the model. Metered like every other AI call (ai:use).
 */
const INTENT_WORDS = {
  connectivity: 'Connection trouble', billing: 'A bill question', sim: 'SIM or PIN', 'plan-change': 'Wants to change plan',
  cancellation: 'Thinking of leaving', delivery: 'A delivery', porting: 'Keeping their number', other: 'Something else',
};

export default function Chats() {
  const [sessions, setSessions] = useState([]);
  const [active, setActive] = useState(null);
  const [msgs, setMsgs] = useState([]);
  const [text, setText] = useState('');
  const [intent, setIntent] = useState(null); // null | 'loading' | {intent, confidence, summary, reply, forMessage}
  const bodyRef = useRef(null);
  const lastAsked = useRef(null);

  const load = async () => {
    try {
      const res = await authFetch(`${BASE}/sessions`);
      setSessions(await res.json());
    } catch { /* transient */ }
  };
  const loadMsgs = async (id) => {
    try {
      const res = await authFetch(`${BASE}/session/${id}/messages`);
      const all = await res.json();
      setMsgs(all);
      setTimeout(() => { if (bodyRef.current) bodyRef.current.scrollTop = bodyRef.current.scrollHeight; }, 50);
    } catch { /* transient */ }
  };

  useEffect(() => { load(); const t = setInterval(load, 6000); return () => clearInterval(t); }, []);
  useEffect(() => {
    if (!active) return undefined;
    setIntent(null); lastAsked.current = null;
    loadMsgs(active);
    const t = setInterval(() => loadMsgs(active), 4000);
    return () => clearInterval(t);
  }, [active]);

  // live intent: the customer's newest message is the trigger, never a timer
  useEffect(() => {
    if (!active || !hasRole('ai:use')) return;
    const lastCustomer = [...msgs].reverse().find((m) => m.author === 'customer');
    if (!lastCustomer || lastAsked.current === lastCustomer.id) return;
    lastAsked.current = lastCustomer.id;
    setIntent('loading');
    aiChatIntent(msgs).then((r) => setIntent({ ...r, forMessage: lastCustomer.id }))
      .catch((e) => setIntent({ intent: 'other', confidence: 0, summary: `Intent unavailable (${e.message})`, reply: null, forMessage: lastCustomer.id }));
  }, [msgs, active]);

  async function reply(line = text) {
    line = line.trim();
    if (!line || !active) return;
    setText('');
    await authFetch(`${BASE}/session/${active}/message`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: line }),
    });
    loadMsgs(active);
    load();
  }

  const session = sessions.find((s) => s.id === active);

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '340px 1fr', gap: 16, alignItems: 'start' }}>
      <div>
        <h2>Live chats</h2>
        {sessions.length === 0 && <p className="dim">No open conversations.</p>}
        {sessions.map((s) => (
          <button key={s.id} onClick={() => setActive(s.id)} data-testid="chat-session-row" data-session-id={s.id}
            style={{ display: 'block', width: '100%', textAlign: 'left', marginBottom: 8,
              padding: '10px 12px', borderRadius: 8, cursor: 'pointer',
              border: active === s.id ? '2px solid var(--accent, #2563eb)' : '1px solid var(--line, #ddd)',
              background: 'var(--card, #fff)' }}>
            <b style={{ fontSize: 13 }}>{s.channel === 'guest' ? 'Guest' : (s.partyId || '').slice(0, 8)}
              {' '}· {s.status}{s.ticketId ? ` · ${s.ticketId.slice(0, 8)}` : ''}</b>
            <span style={{ display: 'block', fontSize: 12.5, color: '#777', overflow: 'hidden',
              textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{s.lastMessage}</span>
          </button>
        ))}
      </div>
      <div>
        <h2>{active ? 'Conversation' : 'Pick a chat'}</h2>
        {active && (
          <div style={{ border: '1px solid var(--line, #ddd)', borderRadius: 10, background: 'var(--card, #fff)' }}>
            {hasRole('ai:use') && (
              <div className="chat-intent" data-testid="chat-intent">
                {intent === null && <span className="dim small">Waiting for the customer's first message…</span>}
                {intent === 'loading' && <span className="dim small">Reading the conversation…</span>}
                {intent && intent !== 'loading' && (
                  <>
                    <span className={`chip intent-${intent.intent}`} data-testid={`chat-intent-${intent.intent}`} title={`confidence ${Math.round((intent.confidence || 0) * 100)}%`}>
                      {INTENT_WORDS[intent.intent] || intent.intent} · {Math.round((intent.confidence || 0) * 100)}%
                    </span>
                    <span className="small" data-testid="chat-intent-summary">{intent.summary}</span>
                    {intent.reply && (
                      <button className="ghost small" data-testid="chat-intent-use" title="Put the suggested reply in the box — you send it, or rewrite it"
                        onClick={() => { setText(intent.reply); desk('suggestion.accept', 'chat:reply', { intent: intent.intent }); }}>Use reply</button>
                    )}
                    {session?.partyId && <Link className="ghost small linkbtn" data-testid="chat-open-customer" to={`/customer/${session.partyId}`}>Open customer →</Link>}
                  </>
                )}
              </div>
            )}
            <div ref={bodyRef} style={{ height: 380, overflowY: 'auto', padding: 14 }} data-testid="agent-chat-body">
              {msgs.map((m) => (
                <div key={m.id} style={{ marginBottom: 8, textAlign: m.author === 'agent' ? 'right' : 'left' }}>
                  <span style={{ display: 'inline-block', maxWidth: '80%', padding: '7px 11px',
                    borderRadius: 10, fontSize: 13.5,
                    background: m.author === 'agent' ? 'var(--accent, #2563eb)' : 'rgba(127,127,127,.12)',
                    color: m.author === 'agent' ? '#fff' : 'inherit' }}>
                    <b style={{ display: 'block', fontSize: 10.5, opacity: .75 }}>{m.author}</b>
                    {m.body}
                  </span>
                </div>
              ))}
            </div>
            <form style={{ display: 'flex', gap: 6, padding: 10, borderTop: '1px solid var(--line, #eee)' }} onSubmit={(e) => { e.preventDefault(); reply(); }}>
              <input value={text} onChange={(e) => setText(e.target.value)} data-testid="agent-chat-input" aria-label="Reply to the customer"
                placeholder="Reply as agent — the bot steps aside…"
                style={{ flex: 1, border: '1px solid var(--line, #ccc)', borderRadius: 8, padding: '8px 10px' }} />
              <button type="submit" data-testid="agent-chat-send"
                style={{ borderRadius: 8, padding: '8px 14px', cursor: 'pointer' }}>Send</button>
            </form>
          </div>
        )}
      </div>
    </div>
  );
}
