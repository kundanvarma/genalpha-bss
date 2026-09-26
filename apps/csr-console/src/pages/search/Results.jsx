/*
 * Typed search results — CSR-UX-002 (#146) and CSR-UX-003 (#147).
 *
 * One row is ONE target. The whole row is the anchor, so there is no small
 * blue word to hunt for: pointer or keyboard, the thing you aim at is the
 * thing you open. Hover and focus are both visible, and focus is reachable
 * two ways — Tab through the list, or arrow down into it from the box.
 *
 * Each row says what it IS (the type badge) before it says anything else, and
 * carries just enough to tell two similar objects apart: a reference, the
 * type, the contact details, the holding count. Identification and fast
 * selection — deliberately not a mini dashboard.
 */
import { Link } from 'react-router-dom';
import { grouped, typeLabel } from './typedSearch.js';

/** Move focus along the rendered rows; ← the arrow keys the review asked for. */
function moveFocus(container, from, delta) {
  if (!container) return;
  const rows = [...container.querySelectorAll('[data-result-row]')];
  if (!rows.length) return;
  const at = rows.indexOf(from);
  const next = rows[Math.min(rows.length - 1, Math.max(0, (at < 0 ? 0 : at + delta)))];
  if (next) next.focus();
}

function Facts({ facts }) {
  return (
    <span className="dim small resultfacts">
      {facts.map((f, i) => (
        <span key={`${f.label}-${i}`} className="fact" title={f.title || undefined}>
          <span className="factlabel">{f.label}</span>
          <span className={f.mono ? 'factvalue mono' : 'factvalue'}>{f.value}</span>
        </span>
      ))}
    </span>
  );
}

function Row({ result, onKeyDown }) {
  const body = (
    <>
      <span className="resultbody">
        <span className="resulthead">
          <span className="typebadge" data-type={result.type}>{typeLabel(result.type)}</span>
          <strong>{result.title}</strong>
        </span>
        <Facts facts={result.facts} />
        {(result.holding || result.why) && (
          <span className="dim small resultwhy">
            {[result.holding, result.why].filter(Boolean).join(' — ')}
          </span>
        )}
      </span>
      <span className="rowend">
        {result.chips.map((c) => <span key={c.text} className={`state ${c.tone}`}>{c.text}</span>)}
      </span>
    </>
  );
  // A result whose owner is unknown has nowhere honest to go: it still shows,
  // as a row that says so, rather than a link that lands on a guess.
  if (!result.to) {
    return (
      <div className="row result noplace" data-result-row data-testid={`result-${result.type}`}
           tabIndex={0} onKeyDown={onKeyDown} aria-label={`${typeLabel(result.type)} ${result.title} — no customer on record`}>
        {body}
      </div>
    );
  }
  return (
    <Link className="row rowlink result" to={result.to} data-result-row data-result-id={result.id}
          data-testid={`result-${result.type}`} onKeyDown={onKeyDown}
          aria-label={`${typeLabel(result.type)}: ${result.title}`}>
      {body}
    </Link>
  );
}

/**
 * The typed groups. `listRef` is the element arrow keys walk, shared with the
 * search box so ↓ from the box lands on the first result.
 */
export default function Results({ results, listRef }) {
  const onKeyDown = (e) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      moveFocus(listRef.current, e.currentTarget, e.key === 'ArrowDown' ? 1 : -1);
    }
  };
  return (
    <div ref={listRef} data-testid="search-results">
      {grouped(results).map((g) => (
        <section className="resultgroup" key={g.type} aria-labelledby={`group-${g.type}`}>
          <h2 className="grouphead" id={`group-${g.type}`} data-testid={`group-${g.type}`}>
            {g.plural} <span className="dim small">{g.results.length}</span>
          </h2>
          <div className="rows">
            {g.results.map((r) => <Row key={`${r.type}-${r.id}`} result={r} onKeyDown={onKeyDown} />)}
          </div>
        </section>
      ))}
    </div>
  );
}

export { moveFocus };
