import { useEffect, useState } from 'react';
import { markNotificationRead, myNotifications } from '../api.js';

export default function Notifications() {
  const [messages, setMessages] = useState(null);
  const [error, setError] = useState(null);

  const load = () => myNotifications().then(setMessages).catch((e) => setError(e.message));
  useEffect(() => { load(); }, []);

  if (error) return <p className="error">{error}</p>;
  if (!messages) return <p className="dim">Loading notifications…</p>;
  if (!messages.length) return <p className="dim">Nothing yet — we'll let you know when something happens.</p>;

  async function read(id) {
    try {
      await markNotificationRead(id);
      load();
    } catch (e) {
      setError(e.message);
    }
  }

  return (
    <>
      <h1>Notifications</h1>
      <div className="rows">
        {messages.map((m) => (
          <div className={m.status === 'read' ? 'row noteread' : 'row'} key={m.id}>
            <div>
              <strong>{m.subject}</strong>
              <div className="dim small">{m.content}</div>
              <div className="dim small">{m.sendTime ? new Date(m.sendTime).toLocaleString() : ''}</div>
            </div>
            {m.status !== 'read' && (
              <button className="ghost" onClick={() => read(m.id)}>Mark read</button>
            )}
          </div>
        ))}
      </div>
    </>
  );
}
