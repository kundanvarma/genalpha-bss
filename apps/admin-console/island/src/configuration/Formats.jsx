import { useCallback, useEffect, useState } from 'react';
import { SYNTAXES, syntaxLabel } from './api.js';
import { moment, plain } from '../bills/words.js';

/* Bill formats — what a country's electronic invoice actually is.
 *
 * Adding a country is an insert, not a deploy: the tenant's distribution
 * format points at one of these by code and the renderer follows the row. It
 * lives under Configuration because nobody opens it to do today's work, and
 * having it among the daily pages was what made the daily path long.
 */

const EMPTY = { code: '', name: '', syntax: 'ubl', customizationId: '', profileId: '', paymentReference: false };

function Editor({ draft, setDraft, existing, onSave, onCancel, busy, said, syntaxes }) {
  return (
    <form data-testid="format-editor" onSubmit={(e) => { e.preventDefault(); onSave(); }}
      style={{ display: 'grid', gap: 12, maxWidth: 620, margin: '0 0 16px' }}>
      <h3 style={{ margin: 0, fontSize: '1rem' }}>
        {existing ? `Bill format ${draft.name || draft.code}` : 'New bill format'}
      </h3>
      <label style={{ display: 'grid', fontSize: '0.85rem' }}>
        Code — what a tenant&apos;s distribution format points at
        <input data-testid="format-code" value={draft.code} required disabled={Boolean(existing)}
          onChange={(e) => setDraft({ ...draft, code: e.target.value.trim() })} placeholder="e.g. ehf" />
      </label>
      <label style={{ display: 'grid', fontSize: '0.85rem' }}>
        Name
        <input data-testid="format-name" value={draft.name} required
          onChange={(e) => setDraft({ ...draft, name: e.target.value })}
          placeholder="e.g. EHF 3.0 (Norway)" />
      </label>
      <label style={{ display: 'grid', fontSize: '0.85rem' }}>
        Syntax — EN 16931 carries UBL and CII; this tenant&apos;s own are here too
        <select data-testid="format-syntax" value={draft.syntax}
          onChange={(e) => setDraft({ ...draft, syntax: e.target.value })}>
          {syntaxes.map((s) => <option key={s} value={s}>{syntaxLabel(s)}</option>)}
        </select>
      </label>
      <label style={{ display: 'grid', fontSize: '0.85rem' }}>
        CustomizationID the document declares
        <input data-testid="format-customization" value={draft.customizationId || ''}
          onChange={(e) => setDraft({ ...draft, customizationId: e.target.value })} />
      </label>
      <label style={{ display: 'grid', fontSize: '0.85rem' }}>
        ProfileID
        <input data-testid="format-profile" value={draft.profileId || ''}
          onChange={(e) => setDraft({ ...draft, profileId: e.target.value })} />
      </label>
      <label style={{ fontSize: '0.85rem' }}>
        <input type="checkbox" data-testid="format-payref" checked={Boolean(draft.paymentReference)}
          onChange={(e) => setDraft({ ...draft, paymentReference: e.target.checked })} />
        {' '}A payment reference is required (Norway NO-R / KID)
      </label>
      {said ? <p data-testid="format-said" style={{ margin: 0, color: 'var(--danger, #b3261e)' }}>{said}</p> : null}
      <div style={{ display: 'flex', gap: 8 }}>
        <button type="submit" data-testid="format-save" disabled={busy}>
          {busy ? 'Saving…' : 'Save the format'}
        </button>
        <button type="button" className="ghost" data-testid="format-cancel" onClick={onCancel}>Cancel</button>
      </div>
    </form>
  );
}

export function Formats({ api }) {
  const [state, setState] = useState({ loading: true });
  const [editing, setEditing] = useState(null);
  const [draft, setDraft] = useState(EMPTY);
  const [busy, setBusy] = useState(false);
  const [said, setSaid] = useState('');

  const load = useCallback(() => api.formats().then((rows) => (rows
    ? { loading: false, rows }
    : { loading: false, rows: [], why: 'Billing could not be reached.' })), [api]);

  useEffect(() => {
    let alive = true;
    load().then((next) => { if (alive) setState(next); });
    return () => { alive = false; };
  }, [load]);

  const open = (row) => {
    setSaid('');
    setEditing(row ? row.code : '');
    setDraft(row ? { ...EMPTY, ...row } : EMPTY);
  };

  const save = async () => {
    setBusy(true);
    setSaid('');
    const res = await api.saveFormat(draft, Boolean(editing));
    if (!res || !res.ok) {
      let why = 'Billing would not take that format.';
      try {
        const answer = await res.json();
        if (answer && answer.message) why = plain(answer.message);
      } catch { /* the status is the answer */ }
      setSaid(why);
      setBusy(false);
      return;
    }
    setBusy(false);
    setEditing(null);
    setState(await load());
  };

  if (state.loading) return <p>Reading the bill formats…</p>;
  if (state.why) return <p className="dim">{state.why}</p>;

  // the picker offers the two syntaxes EN 16931 carries AND every syntax this
  // tenant already uses. A select that cannot hold what a row says would quietly
  // rewrite it on the next save — an edifact profile opened and saved would
  // come back as UBL, and nobody would see it happen.
  const syntaxes = [...new Set([...SYNTAXES.map((s) => s.value),
    ...state.rows.map((r) => r.syntax).filter(Boolean)])];

  return (
    <section data-testid="formats">
      <div style={{ display: 'flex', gap: 12, alignItems: 'baseline', marginBottom: 10 }}>
        <p className="dim" style={{ margin: 0, fontSize: 13 }}>
          What each country&apos;s electronic invoice is. A tenant&apos;s distribution format points at one
          of these by code, and the renderer follows the row.
        </p>
        <button type="button" className="ghost" data-testid="format-new"
          style={{ marginLeft: 'auto' }} onClick={() => open(null)}>+ New format</button>
      </div>
      {editing !== null ? (
        <Editor draft={draft} setDraft={setDraft} existing={Boolean(editing)} onSave={save}
          onCancel={() => setEditing(null)} busy={busy} said={said} syntaxes={syntaxes} />
      ) : null}
      <div className="table-wrap">
        <table>
          <thead>
            <tr><th>Format</th><th>Syntax</th><th>Payment reference</th><th>Last changed</th></tr>
          </thead>
          <tbody data-testid="formats-body">
            {state.rows.map((r) => (
              <tr key={r.code}>
                <td>
                  <button type="button" data-testid="format-row" onClick={() => open(r)}
                    style={{ background: 'none', border: 0, padding: 0, font: 'inherit',
                      color: 'var(--teal-text, var(--teal))', cursor: 'pointer', textDecoration: 'underline' }}>
                    {r.name || r.code}
                  </button>
                </td>
                <td>{syntaxLabel(r.syntax)}</td>
                <td>{r.paymentReference ? 'required' : <span className="dim">not required</span>}</td>
                <td>{r.lastUpdate ? moment(r.lastUpdate) : <span className="dim">—</span>}</td>
              </tr>
            ))}
            {state.rows.length === 0 ? (
              <tr><td colSpan={4} className="dim">No bill format is configured yet.</td></tr>
            ) : null}
          </tbody>
        </table>
      </div>
    </section>
  );
}
