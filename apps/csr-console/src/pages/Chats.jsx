import { useEffect, useRef, useState } from 'react';
import { authFetch } from '../auth.js';

const BASE = '/ai/v1/careChat/agent';

/**
 * The live-chat desk: every open care-chat conversation (guest and customer),
 * bot transcript included. The moment an agent replies, the session flips to
 * 'agent' and the bot goes silent — from then on it's human-to-human, and the
 * customer's widget shows the replies live.
 */
export default function Chats() {
  const [sessions, setSessions] = useState([]);
  const [active, setActive] = useState(null);
  const [msgs, setMsgs] = useState([]);
  const [text, setText] = useState('');
  const bodyRef = useRef(null);

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
    loadMsgs(active);
    const t = setInterval(() => loadMsgs(active), 4000);
    return () => clearInterval(t);
  }, [active]);

  async function reply() {
    const line = text.trim();
    if (!line || !active) return;
    setText('');
    await authFetch(`${BASE}/session/${active}/message`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: line }),
    });
    loadMsgs(active);
    load();
  }

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '340px 1fr', gap: 16, alignItems: 'start' }}>
      <div>
        <h2>Live chats</h2>
        {sessions.length === 0 && <p className="dim">No open conversations.</p>}
        {sessions.map((s) => (
          <button key={s.id} onClick={() => setActive(s.id)} data-testid="chat-session-row"
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
            <div style={{ display: 'flex', gap: 6, padding: 10, borderTop: '1px solid var(--line, #eee)' }}>
              <input value={text} onChange={(e) => setText(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && reply()} data-testid="agent-chat-input"
                placeholder="Reply as agent — the bot steps aside…"
                style={{ flex: 1, border: '1px solid var(--line, #ccc)', borderRadius: 8, padding: '8px 10px' }} />
              <button onClick={reply} data-testid="agent-chat-send"
                style={{ borderRadius: 8, padding: '8px 14px', cursor: 'pointer' }}>Send</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
