import { useEffect, useMemo, useState } from 'react';
import { workspaceOf } from './api.js';
import { day, labelOf, money, plain, situationOf, toneOf } from './words.js';
import { AdjustmentsSection, BillSection, DeliverySection, HistorySection, JournalSection, PaymentsSection } from './Sections.jsx';

/* One bill, whole: what was charged, what was paid, what was adjusted, whether
 * it was delivered, what it posted, and what has happened to it. The paper's
 * complaint was that understanding one financial event meant visiting four
 * modules; this is the answer to that, and it reads only what already exists.
 *
 * It replaces the list inside the same desk rather than opening a route, so the
 * shell keeps its navigation, its palette and the deep link to #/customerBill. */

/** The dated facts the BSS does record, told as a story in order. */
function historyOf({ bill, payments, credits, disputes, deliveries }) {
  const events = [];
  if (bill.billDate) events.push({ what: `Bill issued for ${money(bill.amountDue)}`, when: bill.billDate });
  for (const d of disputes) {
    events.push({ what: 'Dispute raised', when: d.createdAt, why: d.reason });
    if (d.status && d.status !== 'open') {
      events.push({ what: `Dispute ${d.status}`, when: d.resolvedAt || d.createdAt, why: plain(d.resolutionNote) });
    }
  }
  for (const c of credits) events.push({ what: `Credit note ${c.creditNoteNo} for ${money(c.amount)}`, when: c.issuedAt || c.createdAt, why: plain(c.reason) });
  for (const p of payments) events.push({ what: `Payment ${p.status} — ${money(p.amount)}`, when: p.paymentDate || p.createdAt, why: (p.paymentMethod || {}).label });
  for (const d of deliveries) {
    if (d.sentAt) events.push({ what: `Sent by ${d.channel}`, when: d.sentAt });
    if (d.respondedAt) events.push({ what: `Buyer answered: ${d.buyerStatus}`, when: d.respondedAt, why: plain(d.buyerNote) });
  }
  return events
    .filter((e) => e.when)
    .sort((a, b) => new Date(b.when) - new Date(a.when));
}

export function Workspace({ api, id, billNo, who, onBack }) {
  const [data, setData] = useState({ loading: true });

  useEffect(() => {
    let alive = true;
    workspaceOf(api, id).then((d) => alive && setData(d ? { ...d, loading: false } : { loading: false, missing: true }));
    return () => { alive = false; };
  }, [api, id]);

  const events = useMemo(() => (data.bill ? historyOf(data) : []), [data]);

  const back = (
    <button type="button" onClick={onBack} data-testid="back-to-bills"
      style={{ background: 'none', border: 0, padding: 0, color: 'var(--teal-text, var(--teal))', cursor: 'pointer', font: 'inherit' }}>
      ← All bills
    </button>
  );

  if (data.loading) return <section>{back}<p>Opening {billNo}…</p></section>;
  if (data.missing) return <section>{back}<p className="dim">That bill could not be read.</p></section>;

  const { bill } = data;
  const s = situationOf(bill);
  const tone = toneOf(s.value);
  const colour = { bad: 'var(--danger, #b3261e)', warn: '#b45309', quiet: 'var(--dim)' }[tone] || 'var(--ink)';

  return (
    <section data-testid="bill-workspace">
      {back}
      <header style={{ margin: '10px 0 4px', display: 'flex', gap: 12, alignItems: 'baseline', flexWrap: 'wrap' }}>
        <h2 style={{ margin: 0 }}>{bill.billNo}</h2>
        <span style={{ color: colour, fontWeight: tone === 'bad' ? 600 : 400 }}>{labelOf(s.value)}</span>
        <span className="dim">{money(bill.amountDue)} · {who || 'customer'} · due {day(bill.dueDate || s.currentDueDate)}</span>
      </header>
      <p className="dim" style={{ margin: '0 0 6px', fontSize: 13 }}>{plain(s.reason)}</p>

      <BillSection bill={bill} lines={data.lines} who={who} />
      <PaymentsSection payments={data.payments} plan={bill.installmentPlan} />
      <AdjustmentsSection credits={data.credits} disputes={data.disputes} writtenOff={s.value === 'writtenOff'} />
      <DeliverySection deliveries={data.deliveries} channel={bill.distributionChannel} documents={bill.billDocument} />
      <JournalSection postings={data.postings} />
      <HistorySection events={events} />
    </section>
  );
}
