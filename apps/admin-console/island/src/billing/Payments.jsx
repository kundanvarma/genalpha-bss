import { useCallback, useEffect, useMemo, useState } from 'react';
import { deskReader, totals } from './deskApi.js';
import { amounts, Calm, Head } from './parts.jsx';
import { moment, money, plain, shortId } from '../bills/words.js';

/* Payments — money received, and the money that arrived without a home.
 *
 * Bills are money owed; payments are money received. They are related, so the
 * desk keeps them side by side, but they are not the same job, which is why
 * this is its own area rather than a tab inside Bills.
 *
 * Unapplied cash is stated as the exception it is — "537.00 NOK received, no
 * bill claims it" — with the action that resolves it beside the sentence. Only
 * one of the three actions the paper names has an endpoint today; the other
 * two say so rather than pretending.
 */

function Unapplied({ rows, onApply, busy, said }) {
  if (!rows.length) {
    return <Calm testid="unapplied-clean">✓ Every payment received is matched to a bill.</Calm>;
  }
  return (
    <ul data-testid="unapplied" style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gap: 10 }}>
      {rows.map((u) => (
        <li
          key={u.id}
          data-testid="unapplied-row"
          style={{ border: '1px solid var(--line, #ddd)', borderRadius: 10, padding: '0.7rem 0.9rem' }}
        >
          <b style={{ display: 'block' }}>
            {money(u.amount)} received, no bill claims it
          </b>
          <span className="dim" style={{ fontSize: '0.85rem' }}>
            {plain(u.reason || 'the reference matches nothing on the book')}
            {u.receivedAt ? ` · ${moment(u.receivedAt)}` : ''}
            {/* the service's reason usually quotes the reference already;
                printing it again beside it is noise on a worklist row */}
            {u.reference && !String(u.reason || '').includes(String(u.reference))
              ? ` · reference ${plain(String(u.reference))}` : ''}
          </span>
          <form
            style={{ display: 'flex', gap: 8, marginTop: 8, flexWrap: 'wrap', alignItems: 'center' }}
            onSubmit={(e) => { e.preventDefault(); onApply(u.id, new FormData(e.currentTarget).get('billNo')); }}
          >
            <input
              name="billNo"
              aria-label={`Bill number to match ${money(u.amount)} against`}
              placeholder="Bill number…"
              style={{ minWidth: 220 }}
              required
            />
            <button type="submit" className="ghost" data-testid="unapplied-match" disabled={busy === u.id}>
              {busy === u.id ? 'Matching…' : 'Match to bill'}
            </button>
            <button
              type="button"
              className="ghost"
              data-testid="unapplied-find"
              onClick={() => (window.consoleGoTo ? window.consoleGoTo('customerBill') : null)}
            >
              Find the customer
            </button>
            <button
              type="button"
              className="ghost"
              data-testid="unapplied-refund"
              disabled
              title="Billing serves no refund for cash that was never applied; a refund goes through the payment it came from."
            >
              Refund
            </button>
            {said[u.id] ? <span className="dim" style={{ fontSize: '0.85rem' }}>{said[u.id]}</span> : null}
          </form>
        </li>
      ))}
    </ul>
  );
}

export function Payments({ authFetch }) {
  const api = useMemo(() => deskReader(authFetch), [authFetch]);
  const [s, setS] = useState({ loading: true });
  const [busy, setBusy] = useState(null);
  const [said, setSaid] = useState({});

  const load = useCallback(() => Promise.all([api.payments(100), api.unapplied()])
    .then(([payments, unapplied]) => ({ loading: false, payments: payments || [], unapplied: unapplied || [], reachable: Boolean(payments || unapplied) })), [api]);

  useEffect(() => {
    let alive = true;
    load().then((next) => { if (alive) setS(next); });
    return () => { alive = false; };
  }, [load]);

  const apply = async (id, typed) => {
    setBusy(id);
    const billNo = String(typed || '').trim();
    // the operator types the number a human reads; the endpoint takes the
    // bill's identity, so the number is looked up rather than posted as if
    // it were one — posting the wrong field would have been accepted and
    // then quietly matched nothing
    const bill = await api.billByNo(billNo);
    if (!bill) {
      setSaid((was) => ({ ...was, [id]: `No bill on the book is numbered ${billNo}.` }));
      setBusy(null);
      return;
    }
    const res = await api.applyUnapplied(id, bill.id);
    const ok = res && res.ok;
    let why = ok ? `Matched to bill ${billNo}.` : 'That bill would not take it.';
    if (!ok && res) {
      try {
        const body = await res.json();
        if (body && body.message) why = plain(body.message);
      } catch { /* the status is the answer */ }
    }
    setSaid((was) => ({ ...was, [id]: why }));
    setBusy(null);
    if (ok) setS(await load());
  };

  if (s.loading) return <p>Reading payments…</p>;
  if (!s.reachable) return <p className="dim">Billing could not be reached, so this page would only guess.</p>;

  const received = s.payments || [];
  const unmatched = s.unapplied || [];
  const receivedTotal = totals(received.map((p) => p.amount));
  const unmatchedTotal = totals(unmatched.map((u) => u.amount));

  return (
    <section data-testid="payments-desk">
      <p className="dim" style={{ margin: '0 0 2px', fontSize: 13 }}>
        Money received, and the money that arrived without a home.
      </p>

      <Head sub="The exception in the payment flow: the bank named it, no bill claims it.">Unapplied cash</Head>
      {/* the count goes above the worklist: under a hundred rows of forms it
          is a hundred rows down, which is the same as not being there */}
      {unmatched.length ? (
        <Calm testid="unapplied-total">
          {unmatched.length >= 100 ? 'At least ' : ''}{unmatched.length} waiting, {amounts(unmatchedTotal)} in all
          {unmatched.length >= 100 ? ' — billing serves the hundred most recent, so there may be more' : ''}.
        </Calm>
      ) : null}
      <Unapplied rows={unmatched} onApply={apply} busy={busy} said={said} />

      <Head sub="What arrived and what it settled.">Payments received</Head>
      <Calm testid="received-total">
        {received.length} {received.length === 1 ? 'payment' : 'payments'} on this page{received.length ? `, ${amounts(receivedTotal)}` : ''}.
      </Calm>
      <div className="table-wrap">
        <table>
          <thead>
            <tr><th>Received</th><th>Amount</th><th>Method</th><th>Status</th><th>Against</th></tr>
          </thead>
          <tbody data-testid="payments-body">
            {received.slice(0, 50).map((p) => (
              <tr key={p.id}>
                <td>{moment(p.paymentDate || p.createdAt)}</td>
                <td>{money(p.amount)}</td>
                <td>{plain((p.paymentMethod || {}).name || p.method || '—')}</td>
                <td>{plain(p.status || '—')}</td>
                <td>{(p.targetPayment || [])[0]?.name || (p.correlatorId ? `bill ${shortId(p.correlatorId)}` : '—')}</td>
              </tr>
            ))}
            {received.length === 0 ? (
              <tr><td colSpan={5} className="dim">No payments on this page.</td></tr>
            ) : null}
          </tbody>
        </table>
      </div>

      <Head sub="Received against matched — what reconciliation has left to do.">Reconciliation</Head>
      <Calm testid="reconciliation">
        {unmatched.length === 0
          ? 'Everything received is matched to a bill.'
          : `${amounts(receivedTotal)} received and matched; ${amounts(unmatchedTotal)} still waiting for a bill.`}
      </Calm>
    </section>
  );
}
