import { useEffect, useRef, useState } from 'react';
import { authFetch, isSignedIn, isCustomer } from './auth.js';
import { t } from './i18n.js';

const BASE = '/ai/v1/careChat';

/**
 * The care chat bubble — every page, guest and customer alike. Guests get
 * the shop assistant (public shelf only); signed-in customers get answers
 * about THEIR OWN account (the backend fetches with their token's subject,
 * so the bot structurally cannot discuss anyone else). "Talk to a human"
 * raises a real ticket with the transcript, and if an agent picks the chat
 * up in the CSR desk, their replies land here live.
 */
export default function ChatWidget() {
  const [open, setOpen] = useState(false);
  const [session, setSession] = useState(null);
  const [msgs, setMsgs] = useState([]);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState('open');
  const bodyRef = useRef(null);
  const authed = isSignedIn() && isCustomer();
  const api = (path, opts) => (authed
    ? authFetch(BASE + path, opts)
    : fetch(BASE + '/guest' + path, opts));

  const scroll = () => {
    setTimeout(() => {
      if (bodyRef.current) bodyRef.current.scrollTop = bodyRef.current.scrollHeight;
    }, 50);
  };

  async function ensureSession() {
    if (session) return session;
    const res = await api('/session', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
    const s = await res.json();
    setSession(s.id);
    setMsgs([{ author: 'bot', body: authed
      ? t('Hi! Ask me about your bills, usage or orders — or say "human" and I\'ll raise a ticket.')
      : t('Hi! I can help you pick a plan. For account questions, please sign in.') }]);
    return s.id;
  }

  async function send() {
    const line = text.trim();
    if (!line || busy) return;
    setBusy(true);
    setText('');
    setMsgs((m) => [...m, { author: 'customer', body: line }]);
    scroll();
    try {
      const id = await ensureSession();
      const res = await api(`/session/${id}/message`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: line }),
      });
      const out = await res.json();
      setStatus(out.status || 'open');
      if (out.reply) setMsgs((m) => [...m, { author: 'bot', body: out.reply }]);
    } catch {
      setMsgs((m) => [...m, { author: 'bot', body: t('Something went wrong — try again?') }]);
    }
    setBusy(false);
    scroll();
  }

  async function human() {
    if (!session || busy) return;
    setBusy(true);
    try {
      const res = await api(`/session/${session}/escalate`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
      });
      const out = await res.json();
      setStatus('escalated');
      setMsgs((m) => [...m, { author: 'bot',
        body: t('Ticket raised') + ` (${out.ticketId}) — ` + t('a human will pick this up.') }]);
    } catch { /* leave as is */ }
    setBusy(false);
    scroll();
  }

  // agent replies arrive by polling while the panel is open
  useEffect(() => {
    if (!open || !session) return undefined;
    const timer = setInterval(async () => {
      try {
        const res = await api(`/session/${session}/messages`);
        const all = await res.json();
        if (Array.isArray(all) && all.length > msgs.length) {
          setMsgs(all);
          scroll();
        }
      } catch { /* transient */ }
    }, 4000);
    return () => clearInterval(timer);
  }, [open, session, msgs.length]);

  return (
    <div style={{ position: 'fixed', right: 18, bottom: 18, zIndex: 60 }} data-testid="care-chat">
      {open && (
        <div style={{ width: 330, height: 430, display: 'flex', flexDirection: 'column',
          background: 'var(--card, #fff)', border: '1px solid var(--line, #ddd)',
          borderRadius: 12, boxShadow: '0 12px 32px rgba(0,0,0,.18)', overflow: 'hidden', marginBottom: 10 }}>
          <div style={{ padding: '10px 14px', fontWeight: 700, borderBottom: '1px solid var(--line, #eee)',
            display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span>{t('Chat with us')}</span>
            <span style={{ fontSize: 11, color: '#888' }} data-testid="chat-status">
              {status === 'agent' ? t('agent connected') : status === 'escalated' ? t('ticket raised') : t('assistant')}
            </span>
          </div>
          <div ref={bodyRef} style={{ flex: 1, overflowY: 'auto', padding: 12 }} data-testid="chat-body">
            {msgs.map((m, i) => (
              <div key={m.id || i} style={{ marginBottom: 8, textAlign: m.author === 'customer' ? 'right' : 'left' }}>
                <span data-testid={`chat-msg-${m.author}`} style={{ display: 'inline-block', maxWidth: '85%',
                  padding: '7px 11px', borderRadius: 10, fontSize: 13.5, lineHeight: 1.45,
                  background: m.author === 'customer' ? 'var(--accent, #2563eb)' : 'rgba(127,127,127,.12)',
                  color: m.author === 'customer' ? '#fff' : 'inherit' }}>
                  {m.author === 'agent' && <b style={{ display: 'block', fontSize: 11 }}>{t('Agent')}</b>}
                  {m.body}
                </span>
              </div>
            ))}
            {busy && <div style={{ fontSize: 12, color: '#888' }}>…</div>}
          </div>
          <div style={{ display: 'flex', gap: 6, padding: 10, borderTop: '1px solid var(--line, #eee)' }}>
            <input value={text} onChange={(e) => setText(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && send()}
              onFocus={ensureSession}
              placeholder={t('Type a message…')} data-testid="chat-input"
              style={{ flex: 1, border: '1px solid var(--line, #ccc)', borderRadius: 8, padding: '8px 10px', fontSize: 13.5 }} />
            <button onClick={send} disabled={busy} data-testid="chat-send"
              style={{ border: 'none', borderRadius: 8, padding: '8px 12px', cursor: 'pointer' }}>➤</button>
          </div>
          {session && status === 'open' && (
            <button onClick={human} data-testid="chat-human"
              style={{ border: 'none', borderTop: '1px solid var(--line, #eee)', background: 'transparent',
                padding: '8px', fontSize: 12.5, cursor: 'pointer', color: '#888' }}>
              {t('Talk to a human instead')}
            </button>
          )}
        </div>
      )}
      <button onClick={() => setOpen(!open)} aria-label="chat" data-testid="chat-bubble"
        style={{ width: 54, height: 54, borderRadius: '50%', border: 'none', cursor: 'pointer',
          background: 'var(--accent, #2563eb)', color: '#fff', fontSize: 22, float: 'right',
          boxShadow: '0 8px 22px rgba(0,0,0,.25)' }}>
        {open ? '×' : '💬'}
      </button>
    </div>
  );
}
