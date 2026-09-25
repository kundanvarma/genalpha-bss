import { useEffect, useState } from 'react';
import { partyOf, totals } from './deskApi.js';
import { amounts, Head } from './parts.jsx';
import { day, moment, money, plain } from '../bills/words.js';
import { LADDER, restriction } from './ladder.js';

/* One collection case, whole.
 *
 * The paper asks a case to aggregate six things, so an agent does not hop
 * between modules to understand one customer's trouble: the debt, the
 * commitments made, where it stands on the ladder, what has been said to the
 * customer, what has been restricted, and the risk. Each section states what
 * it found or says plainly that there is none — an empty box tells an agent
 * nothing.
 */

function Part({ title, testid, children, none }) {
  return (
    <section data-testid={testid} style={{ border: '1px solid var(--line, #ddd)', borderRadius: 10, padding: '0.8rem 1rem' }}>
      <h4 style={{ margin: '0 0 6px', fontSize: '0.92rem' }}>{title}</h4>
      {children || <p className="dim" style={{ margin: 0, fontSize: '0.88rem' }}>{none}</p>}
    </section>
  );
}

export function Case({ api, id, who, onBack }) {
  const [s, setS] = useState({ loading: true });

  useEffect(() => {
    let alive = true;
    (async () => {
      const row = await api.caseOf(id);
      if (!row) { if (alive) setS({ loading: false }); return; }
      const party = partyOf(row);
      // asked for by customer, so the case does not depend on whose bills
      // happened to fall on the first page of a book with thousands on it
      const [bills, notices, risk] = await Promise.all([
        party ? api.billsOf(party) : Promise.resolve([]),
        party ? api.noticesFor(party) : Promise.resolve([]),
        party ? api.riskOf(party) : Promise.resolve([]),
      ]);
      if (alive) setS({ loading: false, row, bills: bills || [], notices: notices || [], risk: risk || [], party });
    })();
    return () => { alive = false; };
  }, [api, id]);

  if (s.loading) return <p>Reading the case…</p>;
  if (!s.row) return <p className="dim">That case could not be read.</p>;

  const c = s.row;
  const holds = c.holds || {};
  const promise = holds.promiseToPay;
  const owing = (s.bills || []).filter((b) => /overdue|outstanding|partiallyPaid|arrangement/.test(((b.billSituation || {}).value) || ''));
  const restricted = restriction(c);
  const billed = owing.length ? amounts(totals(owing.map((b) => b.amountDue))) : '';
  const assessment = (s.risk || [])[0] || null;
  const result = assessment ? (assessment.riskAssessmentResult || {}) : {};
  // a notice is a message this desk sent about money, not every marketing
  // mail: the ladder's own letters, and the line being paused or restored
  // "instal" alone would catch "Installer booked", which is an appointment,
  // not a word about the debt
  const notices = (s.notices || []).filter((m) => /dunning|collection|reminder|demand|overdue|instalment|installment|bill|invoice|paused|back on|restored|restriction|terminat/i
    .test(`${m.subject || ''} ${m.source || ''} ${m.messageType || ''}`));
  // the case's own warning date is a fact even when no message is logged
  // against it; saying only "nothing has been sent" next to "last warned
  // 27 Aug" is the screen contradicting itself
  const saidNothing = c.warnedAt
    ? `No notice for this debt is in the message log, though the case records a warning on ${day(c.warnedAt)}.`
    : 'Nothing has been sent about this debt.';

  return (
    <section data-testid="collection-case">
      <button type="button" className="ghost" onClick={onBack} style={{ marginBottom: 10 }}>← All cases</button>
      <h2 style={{ margin: '0 0 2px', fontSize: '1.15rem' }}>{who ? plain(who) : 'This customer'}</h2>
      <p className="dim" style={{ margin: '0 0 12px', fontSize: 13 }}>
        {plain(LADDER[c.state] || c.state || 'open')}
        {c.oldestDueAt ? ` · oldest unpaid since ${day(c.oldestDueAt)}` : ''}
      </p>

      <div style={{ display: 'grid', gap: 10, gridTemplateColumns: 'repeat(auto-fit, minmax(17rem, 1fr))' }}>
        <Part title="The debt" testid="case-debt" none="Nothing is owed on this case.">
          {c.overdueBalance || owing.length ? (
            <>
              <b style={{ fontSize: '1.2rem' }}>{money(c.overdueBalance)}</b>
              <p className="dim" style={{ margin: '4px 0 0', fontSize: '0.88rem' }}>
                overdue{owing.length ? ` across ${owing.length} ${owing.length === 1 ? 'bill' : 'bills'}` : ''}
                {/* the billed total is worth saying only where it differs from
                    the balance; printing the same figure twice under two words
                    reads as a mistake */}
                {billed && billed !== money(c.overdueBalance) ? `, ${billed} billed` : ''}
                {c.oldestDueAt ? `, oldest due ${day(c.oldestDueAt)}` : ''}.
              </p>
            </>
          ) : null}
        </Part>

        <Part title="Commitments" testid="case-commitments" none="No promise to pay and no extension.">
          {promise ? (
            <p style={{ margin: 0 }}>
              Promised {money({ value: promise.amount, unit: (c.overdueBalance || {}).unit })}
              {promise.dueAt ? ` by ${day(promise.dueAt)}` : ''}. The ladder waits while the promise holds.
            </p>
          ) : null}
        </Part>

        <Part title="Where it stands" testid="case-stage" none="Nothing taken yet.">
          <p style={{ margin: 0 }}>
            {plain(LADDER[c.state] || c.state || '')}
            {c.stepIndex ? `, step ${c.stepIndex} of the ladder` : ''}
            {c.feeCount ? `, ${c.feeCount} ${c.feeCount === 1 ? 'fee' : 'fees'} charged` : ''}.
            {c.warnedAt ? ` Last warned ${day(c.warnedAt)}.` : ''}
            {holds.dispute ? ' Held: an amount is contested.' : ''}
            {holds.hardship ? ' Held: hardship.' : ''}
          </p>
        </Part>

        <Part title="What we have said" testid="case-communications" none={saidNothing}>
          {notices.length ? (
            <ul style={{ margin: 0, paddingLeft: '1.1rem' }}>
              {notices.slice(0, 6).map((m) => (
                <li key={m.id} style={{ fontSize: '0.88rem' }}>
                  {plain(m.subject || m.messageType || 'notice')} — {moment(m.sendTime)} · {plain(m.deliveryStatus || m.status || '')}
                </li>
              ))}
            </ul>
          ) : null}
        </Part>

        <Part title="Restrictions" testid="case-restrictions" none="Nothing is restricted.">
          {restricted ? <p style={{ margin: 0 }}>{restricted}</p> : null}
        </Part>

        <Part title="Risk" testid="case-risk" none="No risk assessment for this customer.">
          {assessment ? (
            <p style={{ margin: 0 }}>
              {result.riskLevel ? plain(String(result.riskLevel)) : 'assessed'}
              {result.overallScore != null ? `, score ${result.overallScore}` : ''}
              {((result.signal || [])[0] || {}).label ? ` — ${plain(result.signal[0].label)}` : ''}.
            </p>
          ) : null}
        </Part>
      </div>

      <Head sub="What the ledger holds behind this case.">Technical details</Head>
      <details>
        <summary style={{ cursor: 'pointer', fontSize: '0.88rem' }}>Identifiers</summary>
        <p className="dim" style={{ fontSize: '0.85rem', marginTop: 6 }}>
          Case {c.id} · account {c.accountId}{s.party ? ` · party ${s.party}` : ''}
        </p>
      </details>
    </section>
  );
}
