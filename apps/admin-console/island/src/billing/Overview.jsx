import { useEffect, useMemo, useState } from 'react';
import { deskReader, lastRun, totals } from './deskApi.js';
import { amounts, Calm, Drilldown, Exception, Head, num, Queue } from './parts.jsx';
import { day, plain } from '../bills/words.js';
import { isRunning } from './ladder.js';

/* The Billing & Revenue overview — the desk's first screen.
 *
 * It answers three questions and nothing else: are we billing correctly, are
 * customers paying, and what needs attention. The paper's priority rule
 * decides the shape: an exception is loud and leads somewhere; disputes,
 * dunning and arrangements are a queue; outstanding-but-not-due and the run's
 * status are calm status, never a warning; history is folded away. Every
 * figure is a fact a service decided — the situation on a bill, the status of
 * a run, money the bank named that no bill claimed. Nothing here recomputes
 * lateness.
 *
 * And nothing here counts a page and calls it the book. A count is asked of
 * the service, which judges every bill before it pages; an amount is printed
 * only where the desk holds every row it is adding up. Where it does not —
 * the money behind thousands of overdue bills — the screen gives the count
 * and the way in, rather than a total that is quietly one page wide.
 */

const go = (path) => () => (window.consoleGoTo ? window.consoleGoTo(path) : null);

export function Overview({ authFetch }) {
  const api = useMemo(() => deskReader(authFetch), [authFetch]);
  const [s, setS] = useState({ loading: true });

  useEffect(() => {
    let alive = true;
    Promise.all([
      api.countOf(),
      api.countOf('overdue'),
      api.countOf('disputed'),
      api.countOf('outstanding'),
      api.countOf('paid'),
      api.countOf('writtenOff'),
      api.runs(),
      api.unapplied(),
      api.cases(),
    ]).then(([all, overdue, disputed, outstanding, paid, writtenOff, runs, unapplied, cases]) => {
      if (!alive) return;
      setS({
        loading: false,
        reachable: all !== null,
        count: { all, overdue, disputed, outstanding, paid, writtenOff },
        runs: runs || [],
        unapplied: unapplied || [],
        cases: cases || [],
      });
    });
    return () => { alive = false; };
  }, [api]);

  const view = useMemo(() => {
    const { last, failed } = lastRun(s.runs);
    const open = (s.cases || []).filter(isRunning);
    const promises = (s.cases || []).filter((c) => ((c.holds || {}).promiseToPay));
    return {
      last,
      failed,
      open,
      promises,
      // both sums are over every row the desk holds: the case list arrives
      // whole, and the parked rows are the hundred the endpoint serves
      chased: totals(open.map((c) => c.overdueBalance)),
      unappliedTotal: totals((s.unapplied || []).map((u) => u.amount)),
    };
  }, [s]);

  if (s.loading) return <p>Reading the book…</p>;
  if (!s.reachable) return <p className="dim">Billing could not be reached, so this page would only guess.</p>;

  const { last, failed, open, promises, chased, unappliedTotal } = view;
  const n = (v) => (typeof v === 'number' ? v : 0);
  const overdue = n(s.count.overdue);
  const disputed = n(s.count.disputed);
  const outstanding = n(s.count.outstanding);
  const paid = n(s.count.paid);
  const unapplied = (s.unapplied || []).length;
  const calm = !overdue && !unapplied && !failed;

  return (
    <section data-testid="billing-overview">
      <p className="dim" style={{ margin: '0 0 2px', fontSize: 13 }}>
        Are we billing correctly, are customers paying, and what needs attention.
        <span> {num(s.count.all)} bills on the book.</span>
      </p>

      <Head sub="Money or work that is stuck. Everything here is something a person does next.">What needs attention</Head>
      <div data-testid="attention" data-clean={calm ? 'yes' : 'no'} style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        <Exception
          testid="attention-overdue"
          clean="No overdue bills"
          count={overdue}
          label={`${overdue === 1 ? 'bill is' : 'bills are'} overdue`}
          hint="Past the due date with no arrangement in place. Open the bills to see the money."
          onOpen={go('customerBill')}
        />
        <Exception
          testid="attention-unapplied"
          clean="All payments matched"
          count={unapplied}
          amount={amounts(unappliedTotal)}
          // the endpoint serves a hundred parked rows and reports no total, so
          // a full page is a floor and is said as one rather than as the count
          label={`received across ${unapplied >= 100 ? 'at least ' : ''}${num(unapplied)} ${unapplied === 1 ? 'payment' : 'payments'}, ${unapplied === 1 ? 'no bill claims it' : 'no bill claims them'}`}
          hint={unapplied >= 100
            ? 'Money the bank named that no bill cleanly matches. Billing serves the hundred most recent, so there may be more.'
            : 'Money the bank named that no bill cleanly matches.'}
          onOpen={go('payments')}
        />
        <Exception
          testid="attention-run"
          clean="The last billing run finished"
          count={failed ? 1 : 0}
          amount={failed ? 'Run failed' : null}
          label={failed ? plain(failed.lastError || 'the run did not finish') : ''}
          hint={failed ? `Started ${day(failed.startedAt)}.` : ''}
          onOpen={go('customerBill')}
        />
      </div>

      <Head sub="Work in hand: someone is on it, or someone should be.">The queue</Head>
      <Queue
        items={[
          { key: 'disputes', count: disputed, label: `${disputed === 1 ? 'bill' : 'bills'} in dispute`, note: 'the ladder stops while money is contested', onOpen: go('dispute') },
          { key: 'cases', count: open.length, label: `collection ${open.length === 1 ? 'case' : 'cases'} open`, note: `${amounts(chased)} being chased`, onOpen: go('collections') },
          { key: 'arrangements', count: promises.length, label: `payment ${promises.length === 1 ? 'arrangement' : 'arrangements'} in place`, note: 'not chased while the promise holds', onOpen: go('collections') },
        ]}
      />

      <Head sub="Normal states. Nothing here is a problem.">Where the book stands</Head>
      <Calm testid="calm-outstanding">
        {num(outstanding)} {outstanding === 1 ? 'bill is' : 'bills are'} outstanding and not yet due.
      </Calm>
      <Calm testid="calm-paid">{num(paid)} {paid === 1 ? 'bill has' : 'bills have'} been paid.</Calm>
      <Calm testid="calm-run">
        {last
          ? `The last billing run ${/fail|error/i.test(last.status || '') ? 'failed' : 'finished'} on ${day(last.finishedAt || last.startedAt)}.`
          : 'No billing run has been recorded yet.'}
      </Calm>

      <Drilldown testid="overview-history" summary="Earlier runs and settled money">
        <ul style={{ margin: 0, paddingLeft: '1.1rem' }}>
          {(s.runs || []).slice(0, 8).map((r) => (
            <li key={r.id} style={{ fontSize: '0.88rem' }}>
              {day(r.startedAt)} — {plain(r.status || '')}
              {r.billsCreated != null ? `, ${r.billsCreated} bills` : ''}
              {r.lastError ? `, ${plain(r.lastError)}` : ''}
            </li>
          ))}
          {(s.runs || []).length === 0 ? <li className="dim" style={{ fontSize: '0.88rem' }}>No runs recorded.</li> : null}
        </ul>
        <p className="dim" style={{ fontSize: '0.88rem', marginTop: 8 }}>
          Paid: {num(paid)}. Written off: {num(s.count.writtenOff)}.
        </p>
      </Drilldown>
    </section>
  );
}
