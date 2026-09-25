import { useState } from 'react';
import { setting } from './words.js';
import { plain } from '../bills/words.js';

/* Proposing a change to the chart of accounts — rung one of the ladder.
 *
 * This form never writes to the books. It writes down what somebody wants the
 * books to do, with what the account says today beside it, and hands the
 * change to the Configuration page where it is validated, approved and
 * activated. That is the whole point: these settings decide which account real
 * money lands in, and a keystroke is not a decision.
 */
export function ProposeForm({ api, chart, row, onCancel, onDrafted }) {
  const [key, setKey] = useState(row ? row.key : '');
  const [code, setCode] = useState(row ? row.accountCode : '');
  const [name, setName] = useState(row ? row.accountName : '');
  const [value, setValue] = useState(row && row.configValue !== null && row.configValue !== undefined
    ? String(row.configValue) : '');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [said, setSaid] = useState('');

  const picked = chart.find((r) => r.key === key) || null;
  const pick = (next) => {
    setKey(next);
    const found = chart.find((r) => r.key === next);
    setCode(found ? found.accountCode : '');
    setName(found ? found.accountName : '');
    setValue(found && found.configValue !== null && found.configValue !== undefined
      ? String(found.configValue) : '');
  };

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setSaid('');
    const body = { postingKey: key, accountCode: code.trim(), accountName: name.trim(), reason };
    if (picked && picked.setting) body.configValue = value === '' ? null : Number(value);
    const res = await api.draft(body);
    if (!res || !res.ok) {
      let why = 'The subledger would not take that proposal.';
      try {
        const answer = await res.json();
        if (answer && answer.message) why = plain(answer.message);
      } catch { /* the status is the answer */ }
      setSaid(why);
      setBusy(false);
      return;
    }
    onDrafted(await res.json());
  };

  return (
    <section data-testid="propose">
      <h3 style={{ margin: '0 0 4px', fontSize: '1rem' }}>
        {row ? `Propose a change to ${row.accountName}` : 'New account'}
      </h3>
      <p className="dim" style={{ margin: '0 0 12px', fontSize: 13 }}>
        The subledger books against a fixed set of business events. Giving one of them your own account
        code and name is what a new account is here — and it goes live only after it is validated and
        approved.
      </p>
      <form onSubmit={submit} style={{ display: 'grid', gap: 12, maxWidth: 620 }}>
        <label style={{ display: 'grid', fontSize: '0.85rem' }}>
          What kind of money this account holds
          <select data-testid="propose-key" value={key} onChange={(e) => pick(e.target.value)}
            required disabled={Boolean(row)}>
            <option value="">Choose…</option>
            {chart.map((r) => <option key={r.key} value={r.key}>{r.books || r.accountName}</option>)}
          </select>
        </label>
        {picked ? (
          <p className="dim" data-testid="propose-today" style={{ margin: 0, fontSize: '0.85rem' }}>
            Today it books into {picked.accountCode} “{picked.accountName}”
            {picked.postings ? `, and ${picked.postings.toLocaleString()} postings already carry that code.`
              : ', and nothing has been booked to it yet.'}
          </p>
        ) : null}
        <label style={{ display: 'grid', fontSize: '0.85rem' }}>
          Account code in your general ledger
          <input data-testid="propose-code" value={code} onChange={(e) => setCode(e.target.value)}
            placeholder="e.g. 4000" required />
        </label>
        <label style={{ display: 'grid', fontSize: '0.85rem' }}>
          Account name
          <input data-testid="propose-name" value={name} onChange={(e) => setName(e.target.value)}
            placeholder="e.g. Service revenue" required />
        </label>
        {picked && picked.setting ? (
          <label style={{ display: 'grid', fontSize: '0.85rem' }}>
            {picked.setting.charAt(0).toUpperCase() + picked.setting.slice(1)}
            <input data-testid="propose-value" type="number" step="any" value={value}
              onChange={(e) => setValue(e.target.value)}
              placeholder={`now ${setting(picked.configValue)}`} />
          </label>
        ) : null}
        <label style={{ display: 'grid', fontSize: '0.85rem' }}>
          Why (the approver reads this)
          <input data-testid="propose-reason" value={reason} onChange={(e) => setReason(e.target.value)}
            placeholder="e.g. the new chart of accounts takes effect on 1 January" />
        </label>
        {said ? <p data-testid="propose-said" style={{ margin: 0, color: 'var(--danger, #b3261e)' }}>{said}</p> : null}
        <div style={{ display: 'flex', gap: 8 }}>
          <button type="submit" data-testid="propose-submit" disabled={busy || !key}>
            {busy ? 'Writing it down…' : 'Propose the change'}
          </button>
          <button type="button" className="ghost" data-testid="propose-cancel" onClick={onCancel}>Cancel</button>
        </div>
      </form>
    </section>
  );
}
