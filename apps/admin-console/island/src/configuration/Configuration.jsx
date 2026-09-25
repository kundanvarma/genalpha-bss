import { useMemo, useState } from 'react';
import { Deliveries, Shadow } from './Operations.jsx';
import { Formats } from './Formats.jsx';
import { Ladder } from './Ladder.jsx';
import { configReader } from './api.js';

/* Configuration — where live financial configuration is changed, and where
 * the setup pages that nobody needs for today's work now live.
 *
 * Four surfaces, one destination: the ladder over the chart of accounts, the
 * bill formats a country's electronic invoice is made of, the delivery ledger,
 * and the standing shadow bill run. They were four peer tabs in the daily
 * path; the daily path was long because of it.
 */

const AREAS = [
  { key: 'changes', label: 'Financial changes',
    goal: 'Goal: nothing changes the books without being checked, signed for and activated.' },
  { key: 'formats', label: 'Bill formats',
    goal: 'Goal: every country we invoice in has a profile the renderer can follow.' },
  { key: 'deliveries', label: 'Deliveries',
    goal: 'Goal: every bill reaches its partner, and a failure is sent again.' },
  { key: 'shadow', label: 'Shadow billing',
    goal: 'Goal: a price that would bill differently is caught before the invoice is.' },
];

export function Configuration({ authFetch, view }) {
  const api = useMemo(() => configReader(authFetch), [authFetch]);
  // Bill formats, Deliveries and Shadow billing each keep their own tab and
  // land on their own area of this page; Configuration itself opens the ladder
  const [area, setArea] = useState(AREAS.some((a) => a.key === view) ? view : 'changes');

  const current = AREAS.find((a) => a.key === area) || AREAS[0];

  return (
    <section data-testid="configuration">
      <div className="chips" style={{ margin: '0 0 10px' }}>
        {AREAS.map((a) => (
          <button key={a.key} type="button" className={`chip${a.key === area ? ' on' : ''}`}
            data-testid={`configuration-${a.key}`} aria-pressed={a.key === area}
            onClick={() => setArea(a.key)}>
            {a.label}
          </button>
        ))}
      </div>
      <p className="dim" style={{ margin: '0 0 10px', fontSize: 13 }}>{current.goal}</p>
      {area === 'changes' ? <Ladder api={api} /> : null}
      {area === 'formats' ? <Formats api={api} /> : null}
      {area === 'deliveries' ? <Deliveries api={api} /> : null}
      {area === 'shadow' ? <Shadow api={api} /> : null}
    </section>
  );
}
