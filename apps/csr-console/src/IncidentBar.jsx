import { useState } from 'react';

/* One compact line for everything the network knows is wrong right now — count,
 * since when, the names — with the details a click away and a dismiss that
 * lasts the session (a new incident brings the bar back). Per-customer
 * relevance lives on the customer page: Assist says "affects this customer's
 * line" there. Never a wall of repeated red banners. */
function since(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
}

export default function IncidentBar({ problems }) {
  const [expanded, setExpanded] = useState(false);
  const key = problems.map((p) => p.id).sort().join(',');
  const [dismissedKey, setDismissedKey] = useState(() => { try { return sessionStorage.getItem('bss.csr.incidents.dismissed') || ''; } catch { return ''; } });
  if (!problems.length || dismissedKey === key) return null;
  const sorted = [...problems].sort((a, b) => new Date(b.timeRaised || 0) - new Date(a.timeRaised || 0));
  const first = sorted[0];
  const shown = sorted.slice(0, 3);
  const affected = problems.reduce((n, p) => n + (Number(p.affectedNumberOfServices) || 0), 0);
  return (
    <div className="incidentbar" data-testid="outage-banner" role="status">
      <span className="incident-glyph" aria-hidden="true">⚠</span>
      <span className="incident-text">
        <strong>{problems.length === 1 ? 'Network incident' : `${problems.length} network incidents`}</strong>
        {' — '}{shown.map((p) => p.name).join(' · ')}{sorted.length > shown.length && <span className="dim"> +{sorted.length - shown.length} more</span>}
        {affected > 0 && <span className="dim"> · {affected} service{affected === 1 ? '' : 's'} affected</span>}
        {first.timeRaised && <span className="dim"> · since {since(first.timeRaised)}</span>}
      </span>
      <span className="incident-actions">
        <button className="ghost small" onClick={() => setExpanded((x) => !x)} aria-expanded={expanded} data-testid="outage-details">{expanded ? 'Hide' : 'Details'}</button>
        <button className="ghost small" data-testid="outage-dismiss" title="Hide until a new incident appears"
          onClick={() => { setDismissedKey(key); try { sessionStorage.setItem('bss.csr.incidents.dismissed', key); } catch { /* fine */ } }}>Dismiss</button>
      </span>
      {expanded && (
        <ul className="incident-list small">
          {sorted.map((p) => (
            <li key={p.id}>
              <strong>{p.name}</strong>{p.description ? ` — ${p.description}` : ''}
              {p.affectedObject ? <span className="dim"> · on {p.affectedObject}</span> : null}
              {p.priority != null ? <span className="dim"> · priority {p.priority}</span> : null}
            </li>
          ))}
          <li className="dim">Customers on an affected path see it on their page under Assist; a line check on a customer's service names the outage.</li>
        </ul>
      )}
    </div>
  );
}
