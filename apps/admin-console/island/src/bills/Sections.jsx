import { day, labelOf, money, moment, period, plain, shortId, situationOf } from './words.js';

/* The six questions a bill has to answer, one section each. A section with
 * nothing to show says so in a sentence; it never renders an empty box, and it
 * never puts an identifier where a business event belongs. */

export function Section({ title, question, empty, rows, children, testid }) {
  // `rows` is the section's own answer to "do I have any facts?". It has to win
  // over `children`, because a section built from conditional expressions always
  // HAS children — an array of nulls — and testing children alone rendered an
  // empty box where the sentence belongs. A section that passes no rows (the
  // bill itself) always has something to say and keeps its children.
  const nothing = rows ? rows.length === 0 : !children;
  return (
    <section data-testid={testid} style={{ borderTop: '1px solid var(--line)', padding: '16px 0' }}>
      <h3 style={{ margin: '0 0 2px', fontSize: 15 }}>{title}</h3>
      <p className="dim" style={{ margin: '0 0 10px', fontSize: 12.5 }}>{question}</p>
      {nothing ? <p className="dim" style={{ margin: 0 }}>{empty}</p> : children}
    </section>
  );
}

/** Identifiers live here, folded away, never in the headline. */
export function Technical({ pairs }) {
  const shown = pairs.filter(([, v]) => v);
  if (!shown.length) return null;
  return (
    <details style={{ marginTop: 10 }}>
      <summary className="dim" style={{ cursor: 'pointer', fontSize: 12.5 }}>Technical details</summary>
      <dl style={{ display: 'grid', gridTemplateColumns: 'auto 1fr', gap: '4px 12px', margin: '8px 0 0', fontSize: 12.5 }}>
        {shown.map(([k, v]) => (
          <div key={k} style={{ display: 'contents' }}>
            <dt className="dim">{k}</dt>
            <dd style={{ margin: 0, fontFamily: 'ui-monospace, monospace' }}>{v}</dd>
          </div>
        ))}
      </dl>
    </details>
  );
}

const Table = ({ head, children }) => (
  <div className="table-wrap">
    <table>
      <thead><tr>{head.map((h) => <th key={h}>{h}</th>)}</tr></thead>
      <tbody>{children}</tbody>
    </table>
  </div>
);

export function BillSection({ bill, lines, who }) {
  const s = situationOf(bill);
  return (
    <Section testid="section-bill" title="Bill" question="What was charged, and why?">
      <p style={{ margin: '0 0 10px' }}>
        {money(bill.amountDue)} for {who || 'this customer'}, covering {period(bill.billingPeriod)}.
        {' '}{plain(s.reason) || labelOf(s.value)}
        {s.currentDueDate && s.originalDueDate && s.currentDueDate !== s.originalDueDate
          ? ` Originally due ${day(s.originalDueDate)}.` : ''}
      </p>
      {lines.length ? (
        <Table head={['Line', 'Kind', 'Amount']}>
          {lines.map((l) => (
            <tr key={l.id || l.name}>
              <td>{plain(l.name)}</td>
              <td className="dim">{l.type || '—'}</td>
              <td>{money(l.taxExcludedAmount)}</td>
            </tr>
          ))}
        </Table>
      ) : <p className="dim" style={{ margin: 0 }}>This bill carries no charge lines.</p>}
      <Technical pairs={[['Bill id', bill.id], ['Bill number', bill.billNo], ['Billing account', (bill.billingAccount || {}).id]]} />
    </Section>
  );
}

export function PaymentsSection({ payments, plan }) {
  return (
    <Section testid="section-payments" title="Payments" question="What has been paid or allocated?"
      empty="Nothing has been paid against this bill yet." rows={payments.length || plan ? [1] : []}>
      {plan ? (
        <p style={{ margin: '0 0 10px' }}>
          Paid by instalments: {plan.paidCount} of {plan.installments} taken, {money({ value: plan.amountPer, unit: (payments[0] || {}).amount?.unit })} each.
        </p>
      ) : null}
      {payments.length ? (
        <Table head={['Received', 'Amount', 'How', 'State']}>
          {payments.map((p) => (
            <tr key={p.id}>
              <td>{moment(p.paymentDate || p.createdAt)}</td>
              <td>{money(p.amount)}</td>
              <td>{(p.paymentMethod || {}).label || (p.paymentMethod || {})['@type'] || '—'}</td>
              <td className="dim">{p.status}</td>
            </tr>
          ))}
        </Table>
      ) : null}
      <Technical pairs={payments.map((p, i) => [`Payment ${i + 1}`, p.id])} />
    </Section>
  );
}

export function AdjustmentsSection({ credits, disputes, writtenOff }) {
  const any = credits.length || disputes.length || writtenOff;
  return (
    <Section testid="section-adjustments" title="Adjustments" question="What was credited, disputed, corrected or written off?"
      empty="Nothing has been adjusted on this bill." rows={any ? [1] : []}>
      {writtenOff ? <p style={{ margin: '0 0 10px' }}>This bill has been written off.</p> : null}
      {disputes.length ? (
        <Table head={['Raised', 'Reason', 'Where it stands', 'Credited']}>
          {disputes.map((d) => (
            <tr key={d.id}>
              <td>{moment(d.createdAt)}</td>
              <td>{plain(d.reason)}</td>
              <td className="dim">{d.status}{d.resolutionNote ? ` — ${plain(d.resolutionNote)}` : ''}</td>
              <td>{d.creditAmount ? money({ value: d.creditAmount }) : '—'}</td>
            </tr>
          ))}
        </Table>
      ) : null}
      {credits.length ? (
        <Table head={['Credit note', 'Amount', 'Reason', 'Settled by']}>
          {credits.map((c) => (
            <tr key={c.id}>
              <td>{c.creditNoteNo}</td>
              <td>{money(c.amount)}</td>
              <td>{plain(c.reason)}</td>
              <td className="dim">{c.settlement || '—'}</td>
            </tr>
          ))}
        </Table>
      ) : null}
      <Technical pairs={credits.map((c) => [c.creditNoteNo, c.id]).concat(disputes.map((d) => ['Dispute', d.id]))} />
    </Section>
  );
}

export function DeliverySection({ deliveries, channel, documents }) {
  return (
    <Section testid="section-delivery" title="Delivery" question="Was it delivered, and through which channel?"
      empty={`No delivery has been recorded${channel ? ` — this account is set to ${channel}` : ''}.`}
      rows={deliveries}>
      <Table head={['Channel', 'Format', 'Status', 'Sent', 'Attempts']}>
        {deliveries.map((d) => (
          <tr key={d.id}>
            <td>{d.channel}</td>
            <td className="dim">{d.format || '—'}</td>
            <td>{d.status}{d.lastError ? ` — ${plain(d.lastError)}` : ''}</td>
            <td>{d.sentAt ? moment(d.sentAt) : '—'}</td>
            <td>{d.attempts}</td>
          </tr>
        ))}
      </Table>
      {(documents || []).length ? (
        <p className="dim" style={{ margin: '10px 0 0', fontSize: 12.5 }}>
          The document itself: {documents.map((a) => a.name || a.mimeType).join(', ')}.
        </p>
      ) : null}
      <Technical pairs={deliveries.map((d) => ['Delivery', d.id])} />
    </Section>
  );
}

export function JournalSection({ postings }) {
  return (
    <Section testid="section-journal" title="Journal" question="Which accounting postings did it create?"
      empty="This bill has posted nothing to the ledger." rows={postings}>
      {postings.map((e) => (
        <div key={e.id} style={{ marginBottom: 12 }}>
          <p style={{ margin: '0 0 4px' }}>
            <strong>{plain(e.description)}</strong> <span className="dim">· {day(e.entryDate)} · {e.currency}</span>
          </p>
          <Table head={['Account', 'Debit', 'Credit', 'Line']}>
            {(e.lines || []).map((l) => (
              <tr key={`${e.id}-${l.seq}`}>
                <td>{l.accountName} <span className="dim">({l.accountCode})</span></td>
                <td>{l.debit ? l.debit.toFixed(2) : '—'}</td>
                <td>{l.credit ? l.credit.toFixed(2) : '—'}</td>
                <td className="dim">{plain(l.description)}</td>
              </tr>
            ))}
          </Table>
        </div>
      ))}
      <Technical pairs={postings.map((e) => [e.sourceType, e.sourceRef])} />
    </Section>
  );
}

export function HistorySection({ events }) {
  return (
    <Section testid="section-history" title="History" question="Who changed what, when and why?"
      empty="Nothing has happened to this bill since it was issued." rows={events}>
      <ul style={{ margin: 0, paddingLeft: 18 }}>
        {events.map((e, i) => (
          <li key={i} style={{ marginBottom: 6 }}>
            <strong>{e.what}</strong> <span className="dim">· {moment(e.when)}{e.who ? ` · ${e.who}` : ''}</span>
            {e.why ? <div className="dim" style={{ fontSize: 12.5 }}>{e.why}</div> : null}
          </li>
        ))}
      </ul>
      <p className="dim" style={{ margin: '10px 0 0', fontSize: 12.5 }}>
        Assembled from what the BSS records against this bill. Per-field edits are not audited yet.
      </p>
    </Section>
  );
}

export { shortId };
