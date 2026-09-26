import { useState } from 'react';
import { hasRole } from '../../auth.js';
import { ceaseService } from '../../api.js';
import { numberOf } from './ServiceRows.jsx';

/* Ceasing lives apart from the row actions on purpose: it is the one action
 * whose consequences must be read before the click, so it never sits beside
 * Diagnose where a hand can slip. */

/** Ceasing, with the consequence in front of the agent before the click. */
export function DangerZone({ services, agreements = [], id, act }) {
  const [open, setOpen] = useState(false);
  const ceasable = services.filter((sv) => sv.state === 'active' && hasRole('service:write'));
  if (!ceasable.length) return null;
  return (
    <details className="danger-zone" data-testid="danger-zone" open={open} onToggle={(e) => setOpen(e.target.open)}>
      <summary>Danger zone — cease a service</summary>
      <p className="small">Ceasing disconnects the service at once, releases its number to quarantine, ends what depends on it and cannot be undone. Use Pause for a break, Transfer to move it to someone else.</p>
      {ceasable.map((sv) => {
        const number = numberOf(sv);
        const agreement = agreements.find((g) => g.status === 'active' && (g.name || '').toLowerCase().includes((sv.name || '').toLowerCase().split(' ')[0]));
        const consequences = [
          `${sv.name} stops now`,
          number ? `number ${number} is released (quarantined, not reusable at once)` : null,
          agreement ? `the agreement "${agreement.name}" ends — a residual may be billed` : null,
          (sv.serviceRelationship || []).length ? 'dependent services stop with it' : null,
        ].filter(Boolean);
        return (
          <div className="row" key={sv.id}>
            <span>{sv.name} {number && <span className="msisdn">{number}</span>}<span className="dim small" style={{ display: 'block' }}>{consequences.join(' · ')}</span></span>
            <button className="ghost danger" data-testid="cease-service"
                    onClick={() => window.confirm(`Cease ${sv.name}?\n\n• ${consequences.join('\n• ')}\n\nThis cannot be undone.`)
                      && act(() => ceaseService(sv.id, 'ceased by agent'), 'services')}>
              Cease
            </button>
          </div>
        );
      })}
    </details>
  );
}

