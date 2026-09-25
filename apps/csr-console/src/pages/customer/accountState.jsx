/* The Overview's Account card: one line per topic, normal quiet, exceptions
 * saying what to do. Split from Money.jsx so the panels file stays inside the
 * 300-line rule (docs/engineering-conventions.md §2).
 */
import { due, situationOf, stillOwing } from './situation.jsx';

/** One line per topic for the Overview's Account card: normal is quiet, exceptions say what to do. */
export function accountState({ bills, spendPolicies, usage, creditDecisions, methods }) {
  const open = bills.filter(stillOwing);
  const disputed = bills.filter((b) => situationOf(b) === 'disputed'
    || (!situationOf(b) && b.dispute && b.dispute.status === 'open'));
  const overdue = bills.filter((b) => situationOf(b) === 'overdue');
  const arranged = bills.filter((b) => situationOf(b) === 'arrangement');
  const lines = [];
  lines.push(open.length
    ? { level: 'warn', text: `${open.length} open bill${open.length === 1 ? '' : 's'} — ${open.reduce((s, b) => s + due(b), 0).toFixed(2)} ${open[0].amountDue?.unit || ''} due`, area: 'billing' }
    : { level: 'ok', text: 'Billing: current', area: 'billing' });
  // lateness and its excuses, in the words the bill itself uses: an arrangement
  // is NOT overdue, here and in the customer's app at the same moment
  if (overdue.length) lines.push({ level: 'warn', text: `${overdue.length} bill${overdue.length === 1 ? '' : 's'} overdue — the collections ladder starts here`, area: 'billing' });
  if (arranged.length) {
    const until = (arranged[0].billSituation || {}).arrangementUntil;
    lines.push({ level: 'ok', text: `${arranged.length} bill${arranged.length === 1 ? '' : 's'} under a payment arrangement${until ? ` until ${until}` : ''} — reminders pause`, area: 'billing' });
  }
  if (disputed.length) lines.push({ level: 'warn', text: `${disputed.length} bill${disputed.length === 1 ? '' : 's'} under dispute — collection paused`, area: 'billing' });
  for (const m of spendPolicies) {
    const label = { spend: 'Spend cap', content: 'Content services', roaming: 'Roaming' }[m.meterType] || m.meterType;
    if (m.blocked || m.barred) lines.push({ level: 'warn', text: `${label}: ${m.barred ? 'barred' : 'blocked'} — review`, area: 'billing' });
    else if (m.limit && m.accrued && Number(m.accrued.value) >= Number(m.limit.value) * 0.8) lines.push({ level: 'warn', text: `${label}: ${m.accrued.value} of ${m.limit.value} ${m.limit.unit} — near the limit`, area: 'billing' });
    else if (m.enabled && m.limit) lines.push({ level: 'ok', text: `${label}: ${m.accrued ? m.accrued.value : 0} of ${m.limit.value} ${m.limit.unit}`, area: 'billing' });
  }
  const over = usage.filter((u) => u.allowedValue != null && Number(u.usedValue) > Number(u.allowedValue));
  if (over.length) lines.push({ level: 'warn', text: `Over allowance: ${over.map((u) => u.name).join(', ')}`, area: 'billing' });
  const declined = creditDecisions.find((c) => c.decision === 'decline');
  if (declined) lines.push({ level: 'warn', text: 'A credit decision on file was a decline', area: 'billing' });
  if (methods.length) lines.push({ level: 'ok', text: `${methods.length} saved card${methods.length === 1 ? '' : 's'}`, area: 'billing' });
  return lines;
}
