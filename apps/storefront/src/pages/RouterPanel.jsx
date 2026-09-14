import { useEffect, useState } from 'react';
import { t } from '../i18n.js';
import { myRouter, restartMyRouter } from '../api.js';

/* The box at the customer's end, from the operator's equipment system: online or
 * not, how long, how many devices — and the restart that fixes most "internet
 * is dead" calls without a ticket. Reads on mount and again after a restart. */
export default function RouterPanel({ serviceId, compact = false }) {
  const [box, setBox] = useState(undefined); // undefined = reading, null = none
  const [note, setNote] = useState(null);
  const [busy, setBusy] = useState(false);
  const load = () => myRouter(serviceId).then(setBox).catch(() => setBox(null));
  useEffect(() => { load(); }, [serviceId]);
  if (box === undefined) return <span className="dim small" data-testid="router-reading">{t('Checking your router…')}</span>;
  if (!box) return null;
  const days = Math.floor((box.uptimeSeconds || 0) / 86400);
  const restart = async () => {
    if (!window.confirm(t('Restart your router? The connection drops for about a minute.'))) return;
    setBusy(true); setNote(null);
    try {
      const r = await restartMyRouter(serviceId);
      setNote(r.said || t('Restart sent.'));
      setTimeout(load, 4000);
    } catch (e) { setNote(e.message); }
    setBusy(false);
  };
  return (
    <span className={`router ${compact ? 'compact' : ''}`} data-testid={`router-${box.state}`}>
      <span className={box.state === 'online' ? 'ok' : box.state === 'rebooting' ? 'dim' : 'error'}>
        {box.state === 'online' ? `● ${t('Router online')}` : box.state === 'rebooting' ? `◌ ${t('Router restarting')}` : `○ ${t('Router offline')}`}
      </span>
      {box.state === 'online' && <span className="dim small"> · {days} {t('days up')} · {box.wifiClients} {t('devices on Wi-Fi')}{box.firmwareOutdated ? ` · ${t('update pending')}` : ''}</span>}
      {box.state === 'offline' && <span className="dim small"> · {t('last seen')} {box.lastSeen ? new Date(box.lastSeen).toLocaleString() : '—'}</span>}
      {' '}<button className={box.state === 'offline' ? 'primary' : 'ghost'} data-testid="restart-router" disabled={busy || box.state === 'rebooting'} onClick={restart}>{t('Restart router')}</button>
      {note && <span className="dim small" data-testid="router-note"> {note}</span>}
    </span>
  );
}
