import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { t } from '../i18n.js';
import { tokenClaims } from '../auth.js';
import { priceIndex, myProducts, myActiveServices, myUsage, myBills, myOrders, myTickets, myAppointments, myNotifications,
  myCollectionCase, forYou, myRecommendations, listOfferings, loyaltyProgram, myLoyalty, mySpendPolicy,
  myHomeContext, resumeMyService, mySim, myAgreements } from '../api.js';
import { LineDoctor } from './Services.jsx';
import { OfferingCard } from './Shop.jsx';
import RouterPanel from './RouterPanel.jsx';

/* Home — the customer's first screen answers two questions: is everything
 * okay, and what should I do next. Exception-first: the attention zone is empty
 * when nothing needs the customer, and loud with a next step when something
 * does. The attention facts come from the same ontology context the care
 * desk's Assist reads, with the customer's own token — so agent and customer
 * see one truth about an incident, a paused line or an open bill. Then their
 * services as cards with the actions that service can take, money and usage,
 * open work, recent activity, and one commercial suggestion — always last. */

const kindOf = (sv) => {
  const c = String(sv.category || sv.serviceType || '').toLowerCase();
  const n = String(sv.name || '').toLowerCase();
  if (/mobile|sim/.test(c) || /mobile|sim\b|5g|gb\b/.test(n)) return 'mobile';
  if (/broadband|fib|dsl|internet/.test(c) || /fiber|fibre|broadband|dsl|internet/.test(n)) return 'broadband';
  if (/\btv\b|iptv|stream/.test(c) || /\btv\b|stream/.test(n)) return 'tv';
  return 'other';
};
const numberOf = (sv) => (sv.supportingResource || []).map((r) => r.value).find(Boolean) || null;
const placeOf = (sv) => { const p = (sv.place || [])[0]; return p ? (p.name || [p.streetName || p.street1, p.city].filter(Boolean).join(', ')) : null; };
const leaves = (items, into = []) => { for (const it of items || []) { const kids = it.productOrderItem || []; if (kids.length) leaves(kids, into); else if (it.productOffering) into.push(it); } return into; };
const dt = (v) => v ? new Date(v).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '';
const OPEN_BILL = ['new', 'validated', 'sent', 'partiallyPaid'];
const LIVE_ORDER = (o) => !['completed', 'cancelled', 'rejected', 'failed'].includes(o.state);
const partOfDay = () => { const h = new Date().getHours(); return h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening'; };

function Meter({ label, used, allowed, units, warn }) {
  const pct = allowed ? Math.min(100, (used / allowed) * 100) : 0;
  return (
    <div className="usage-meter" data-testid="home-meter">
      <div className="usage-meter-head"><span>{label}</span><span className={warn ? 'error' : 'dim'}>{Math.max(0, allowed - used)} {units} {t('left')} · {used} / {allowed}</span></div>
      <div className="usage-meter-track"><div className={`usage-meter-fill${warn ? ' over' : ''}`} style={{ width: `${pct}%` }} /></div>
    </div>
  );
}

export default function Home() {
  const claims = tokenClaims();
  const me = claims.sub;
  const [loaded, setLoaded] = useState(false); // the record has answered at least once — no 'nothing active' before then
  const [d, setD] = useState({ products: [], services: [], usage: [], agreements: [], bills: [], orders: [], tickets: [], appointments: [], notifications: [], ccase: null, offerings: {}, personal: null, recIds: [], program: null, loyalty: null, spend: [] });
  const [ctx, setCtx] = useState(null); // the ontology's reading; null = reading, {} = unavailable
  const [busy, setBusy] = useState(false);
  const [sims, setSims] = useState({}); // serviceId -> masked ICCID, one read per mobile card
  const [prices, setPrices] = useState({}); // for the lead pick's price line
  const [error, setError] = useState(null);

  const load = () => {
    const soft = (p, fallback) => p.catch(() => fallback);
    Promise.all([
      soft(myProducts(), []), soft(myActiveServices(), []), soft(myUsage(), []), soft(myBills(), []), soft(myOrders(), []),
      soft(myTickets(), []), soft(myAppointments(), []), soft(myNotifications(), []), soft(myCollectionCase(), null),
      soft(listOfferings(), []), soft(forYou(), null), soft(myRecommendations(), []), soft(loyaltyProgram(), null), soft(myLoyalty(), null), soft(mySpendPolicy(), []), soft(myAgreements(), []),
    ]).then(([products, services, usage, bills, orders, tickets, appointments, notifications, ccase, offerings, personal, recs, program, loyalty, spend, agreements]) => {
      const index = Object.fromEntries((Array.isArray(offerings) ? offerings : []).map((o) => [o.id, o]));
      const recIds = personal && personal.items?.length ? personal.items.map((i) => i.id) : (recs[0]?.recommendationItem?.map((i) => i.offering.id) || []);
      // the usage report wraps its meters in `bucket`
      const buckets = Array.isArray(usage) ? usage : (usage && Array.isArray(usage.bucket) ? usage.bucket : []);
      setLoaded(true);
      setD({ products, services: Array.isArray(services) ? services : [], usage: buckets, agreements: Array.isArray(agreements) ? agreements : [], bills, orders, tickets: Array.isArray(tickets) ? tickets : [], appointments: Array.isArray(appointments) ? appointments : [], notifications: Array.isArray(notifications) ? notifications : [], ccase, offerings: index, personal, recIds, program, loyalty, spend: Array.isArray(spend) ? spend : [] });
    });
    setCtx(null);
    myHomeContext(me).then((c) => setCtx(c || {})).catch(() => setCtx({}));
    priceIndex().then(setPrices).catch(() => {});
  };
  useEffect(load, [me]);

  const active = d.services.filter((s) => ['active', 'suspended'].includes(s.state));
  const paused = active.filter((s) => s.state === 'suspended');
  const liveOrders = d.orders.filter(LIVE_ORDER);
  const openTickets = d.tickets.filter((x) => x.status !== 'closed');
  const upcoming = d.appointments.filter((ap) => !['completed', 'cancelled'].includes(ap.status));
  const dataBucket = d.usage.find((b) => b.name === 'Mobile data' && b.allowedValue != null);
  const dataUsed = dataBucket ? Number(dataBucket.usedValue) : 0;
  const dataAllowed = dataBucket ? Number(dataBucket.allowedValue) : 0;
  const nearLimit = dataBucket && dataAllowed > 0 && dataUsed / dataAllowed >= 0.8;
  const latestBill = [...d.bills].sort((a, b) => String(b.billDate || b.billNo).localeCompare(String(a.billDate || a.billNo)))[0];
  const openBills = d.bills.filter((b) => OPEN_BILL.includes(b.state) && Number(b.amountDue?.value ?? 0) > 0);
  const caseOpen = d.ccase && !['closed', 'settled', 'none'].includes(String(d.ccase.state || 'none'));
  const roaming = d.spend.find((m) => m.meterType === 'roaming');
  const incidents = (ctx?.situation || []).filter((s) => s.kind === 'incident');

  // the attention list: the ontology's SUMMARY of the situation — one entry per kind with a severity and
  // whether the customer must act ("5 services are paused", not five rows; "payment plan agreed", not the
  // raw overdue flag) — plus the facts only this page knows (data, an order, a case). Three cards at most,
  // the most serious first; the rest is a count with a way in.
  const LEVEL = { critical: 'danger', warning: 'warn', info: 'info' };
  const RANK = { danger: 0, warn: 1, info: 2 };
  const money = (a, c) => `${Number(a || 0).toFixed(2)}${c ? ' ' + c : ''}`;
  const attention = [];
  const summary = Array.isArray(ctx?.summary) ? ctx.summary : null;
  if (summary) {
    for (const s of summary) {
      const level = LEVEL[s.severity] || 'info';
      if (s.kind === 'incident') attention.push({ kind: 'incident', level, text: `${s.count > 1 ? t('We know about problems on your lines') : t('We know about a problem on your line')}. ${t('Our network team is on it — you do not need to do anything.')}`, to: '/support', cta: t('Check my line'), noAction: true });
      else if (s.kind === 'paused' && s.count === 1) { const sv = active.find((x) => x.id === s.members[0]); if (sv) attention.push({ kind: 'paused', level, text: `${sv.name}${numberOf(sv) ? ' ' + numberOf(sv) : ''} ${t('is paused — nothing is charged and nothing connects until you resume it.')}`, action: 'resume', service: sv, cta: t('Resume') }); }
      else if (s.kind === 'paused') attention.push({ kind: 'paused', level, text: `${s.count} ${t('services are paused — no charges apply while they are paused.')}`, to: '/services#paused', cta: t('Review services') });
      else if (s.kind === 'overdue') attention.push({ kind: 'overdue', level: 'danger', text: `${t('Payment overdue')}: ${money(s.amount, s.currency)}. ${t('Settle it, or agree a payment plan, to keep your services running.')}`, to: '/bills', cta: t('Pay now') });
      else if (s.kind === 'arranged') attention.push({ kind: 'arranged', level: 'info', text: `${t('Payment plan agreed')}: ${money(s.amount, s.currency)} ${t('by')} ${String(s.dueAt || '').slice(0, 10)}. ${t('Nothing else is due until then.')}`, to: '/bills', cta: t('Pay early'), ghost: true, noAction: true });
      else if (s.kind === 'disputed') attention.push({ kind: 'disputed', level: 'info', text: `${t('Part of your bill is under dispute — collection waits while we look at it.')}`, to: '/bills', cta: t('See the bill'), ghost: true, noAction: true });
      else if (s.kind === 'bill') attention.push({ kind: 'bill', level: 'info', text: `${t('A bill is open')}: ${money(s.amount, openBills[0]?.amountDue?.unit)}.`, to: '/bills', cta: t('See the bill'), ghost: true });
    }
  } else if (ctx && Object.keys(ctx).length === 0) {
    // the ontology did not answer: the page's own reading, so Home is never blank on a bad day
    for (const sv of paused) attention.push({ kind: 'paused', level: 'warn', text: `${sv.name}${numberOf(sv) ? ' ' + numberOf(sv) : ''} ${t('is paused — nothing is charged and nothing connects until you resume it.')}`, action: 'resume', service: sv, cta: t('Resume') });
    if (caseOpen && !(d.ccase.holds && d.ccase.holds.promiseToPay)) attention.push({ kind: 'overdue', level: 'danger', text: `${t('Payment overdue')}: ${money(d.ccase.overdueBalance?.value, d.ccase.overdueBalance?.unit)}. ${t('Settle it, or agree a payment plan, to keep your services running.')}`, to: '/bills', cta: t('Pay now') });
    else if (openBills.length) attention.push({ kind: 'bill', level: 'info', text: `${t('A bill is open')}: ${openBills[0].billNo} — ${money(openBills[0].amountDue.value, openBills[0].amountDue.unit)}.`, to: '/bills', cta: t('See the bill'), ghost: true });
  }
  if (nearLimit) attention.push({ kind: 'data', level: 'info', text: `${t('Your data is nearly used up')}: ${Math.max(0, dataAllowed - dataUsed)} ${dataBucket.units} ${t('left of')} ${dataAllowed}.`, to: '/shop?tab=Top-ups', cta: t('Buy extra data') });
  for (const o of liveOrders.slice(0, 1)) attention.push({ kind: 'order', level: 'info', text: `${t('Your order is in progress')}: ${o.description || leaves(o.productOrderItem).map((l) => l.productOffering?.name).filter(Boolean).join(', ')}.`, href: '#open-work', cta: t('Track it'), noAction: true });
  for (const x of openTickets.slice(0, 1)) attention.push({ kind: 'case', level: 'info', text: `${t('Your support case is')} ${x.status}: ${x.name}.`, to: '/support', cta: t('View case'), ghost: true, noAction: true });
  attention.sort((a, b) => RANK[a.level] - RANK[b.level]);
  const shown = attention.slice(0, 3);
  const more = attention.slice(3);
  const healthy = !attention.some((a) => a.level !== 'info');
  const critical = attention.some((a) => a.level === 'danger');

  const activity = useMemo(() => {
    const ev = [];
    for (const o of d.orders) ev.push({ at: o.orderDate, kind: 'order', title: `${t('Order')} ${o.state}: ${o.description || leaves(o.productOrderItem).map((l) => l.productOffering?.name).filter(Boolean).join(', ') || ''}` });
    for (const m of d.notifications) ev.push({ at: m.sendTime, kind: 'message', title: m.subject });
    for (const x of d.tickets) ev.push({ at: x.creationDate, kind: 'case', title: `${t('Support case')} ${x.status}: ${x.name}` });
    return ev.filter((e) => e.at).sort((a, b) => new Date(b.at) - new Date(a.at)).slice(0, 5);
  }, [d.orders, d.notifications, d.tickets]);

  const offers = (ctx?.recommendations || []).filter((r) => r.kind === 'offer' && d.offerings[r.offeringId]).map((r) => ({ ...r, offering: d.offerings[r.offeringId] }));
  const picks = offers.length ? offers.map((r) => r.offering) : d.recIds.map((id) => d.offerings[id]).filter(Boolean).slice(0, 3);
  const cards = active.slice(0, 6); // a household has a handful; the full list lives under Services
  useEffect(() => {
    for (const sv of cards) {
      if (kindOf(sv) === 'mobile' && sims[sv.id] === undefined) {
        setSims((m) => ({ ...m, [sv.id]: null }));
        mySim(sv.id).then((sim) => setSims((m) => ({ ...m, [sv.id]: sim?.iccid || null }))).catch(() => {});
      }
    }
  }, [cards.map((s) => s.id).join(',')]);
  const commitmentOf = (name) => d.agreements.find((g) => g.status === 'active' && g.agreementPeriod?.endDateTime && (g.name || '').toLowerCase().startsWith(String(name || '').toLowerCase().split(' ').slice(0, 2).join(' ')));
  const speedOf = (name) => { const m = String(name || '').match(/(\d{2,4})\s*(mbit|mb|gbit|gb)?/i); return m ? `${m[1]} Mbit/s` : null; };
  const given = claims.given_name || claims.name || '';

  const resume = async (sv) => {
    setBusy(true); setError(null);
    try { await resumeMyService(sv.id); load(); } catch (e) { setError(e.message); }
    setBusy(false);
  };

  const lead = offers[0] || null;
  const alternatives = offers.slice(1, 3);
  const recLink = (r) => `/offering/${r.offeringId}?rec=${encodeURIComponent(r.decisionId || '')}&why=${encodeURIComponent(r.why || '')}`;
  const recommended = (
    <section data-testid="home-recommended" className={healthy ? 'lifted' : ''}>
      <h2>{t('Recommended for you')} <Link className="dim small" to="/shop">{t('Shop')} →</Link></h2>
      {!picks.length && <p className="dim small">{t('Nothing to suggest right now.')}</p>}
      {lead && (
        <div className="lead-pick" data-testid="recommended">
          <OfferingCard offering={lead.offering} prices={prices} href={recLink(lead)} />
          <div className="lead-why">
            <div className="assist-label">{t('Best match')} · {t('Why this?')}</div>
            <p data-testid="foryou-caption">✨ {lead.why}</p>
            <p className="dim small">{t('You decide: choose it, save it for later, or tell us it is not for you — we remember.')}</p>
          </div>
        </div>
      )}
      {lead && alternatives.length > 0 && (
        <div className="cards" data-testid="recommended-more">
          {alternatives.map((r) => (
            <div key={'alt-' + r.offeringId} className="alt-pick">
              <OfferingCard offering={r.offering} prices={prices} href={recLink(r)} />
              <p className="dim small alt-why">{r.why}</p>
            </div>
          ))}
        </div>
      )}
      {!lead && picks.length > 0 && (
        <div className="cards" data-testid="recommended">
          {picks.map((o) => <OfferingCard key={o.id} offering={o} prices={prices} />)}
        </div>
      )}
    </section>
  );

  return (
    <div className="home" data-testid="home">
      <section className="hero home-hero" data-testid="home-greeting">
        <h1>{t(partOfDay())}{given ? `, ${given}` : ''}</h1>
        <p data-testid="home-health" className={critical ? 'error' : !healthy ? 'warn' : 'ok'}>
          {ctx === null && !attention.length ? t('Reading your services…')
            : !healthy ? `${attention.filter((a) => a.level !== 'info').length} ${attention.filter((a) => a.level !== 'info').length === 1 ? t('thing needs your attention') : t('things need your attention')}`
            : attention.length ? `${t('Everything is working')} · ${attention.length} ${attention.length === 1 ? t('update') : t('updates')}`
            : t('Everything looks good')}
        </p>
      </section>
      {error && <p className="error">{error}</p>}

      {/* attention: only when relevant — three at most, the most serious first */}
      <section className="home-attention" data-testid="home-attention" aria-live="polite">
        {ctx === null && <p className="dim small">{t('Reading your services…')}</p>}
        {shown.map((a) => (
          <div key={a.kind + (a.service?.id || '')} className={`callout ${a.level}${a.level === 'info' ? ' compact' : ''}`} data-testid={`attention-${a.kind}`}>
            <span>{a.text}{a.noAction && a.level === 'info' ? <span className="chip kind noaction"> {t('No action needed')}</span> : null}</span>
            {a.action === 'resume' && <button className="primary" disabled={busy} onClick={() => resume(a.service)}>{a.cta}</button>}
            {a.to && <Link className={`${a.ghost ? 'ghost' : 'primary'} linkbtn`} to={a.to}>{a.cta}</Link>}
            {a.href && <a className="ghost linkbtn" href={a.href}>{a.cta}</a>}
          </div>
        ))}
        {more.length > 0 && <p className="dim small" data-testid="attention-more">{t('and')} {more.length} {t('more')} — <Link to={more.some((a) => ['overdue', 'bill', 'arranged'].includes(a.kind)) ? '/bills' : '/services'}>{t('see all')}</Link></p>}
      </section>

      {/* my services */}
      <section data-testid="home-services">
        <h2>{t('My services')} <Link className="dim small" to="/services">{active.length > 6 ? `${t('All')} ${active.length} →` : `${t('Manage')} →`}</Link></h2>
        {!loaded && !cards.length && <p className="dim small">{t('Reading your services…')}</p>}
        {loaded && !cards.length && <p className="dim">{t('Nothing active yet — your plan appears here once an order completes.')} <Link to="/shop">{t('Shop')} →</Link></p>}
        <div className="cards services">
          {cards.map((sv) => {
            const kind = kindOf(sv);
            const number = numberOf(sv);
            const product = d.products.find((p) => (p.realizingService || []).some((r) => r.id === sv.id)) || d.products.find((p) => p.name === sv.name);
            return (
              <div className={`card svc ${kind}`} key={sv.id} data-testid={`service-card-${kind}`}>
                <div className="svc-head">
                  <span className="chip kind">{{ mobile: t('Mobile'), broadband: t('Broadband'), tv: t('TV'), other: t('Service') }[kind]}</span>
                  <span className={`state ${sv.state}`}>{sv.state === 'suspended' ? t('paused') : sv.state}</span>
                </div>
                <h3>{product?.name || sv.name}</h3>
                <div className="dim small facts">
                  {number && <span className="msisdn">{number}</span>}
                  {kind === 'broadband' && placeOf(sv) && <span>{placeOf(sv)}</span>}
                  {kind === 'broadband' && speedOf((product || sv).name) && <span>{speedOf((product || sv).name)}</span>}
                  {kind === 'broadband' && sv.state === 'active' && <RouterPanel serviceId={sv.id} compact />}
                  {kind === 'mobile' && dataBucket && <span className={nearLimit ? 'error' : ''}>{Math.max(0, dataAllowed - dataUsed)} {dataBucket.units} {t('data left')}</span>}
                  {kind === 'mobile' && sims[sv.id] && <span>SIM {sims[sv.id]}</span>}
                  {commitmentOf((product || sv).name) && <span>{t('commitment until')} {String(commitmentOf((product || sv).name).agreementPeriod.endDateTime).slice(0, 10)}</span>}
                </div>
                <div className="svc-actions">
                  {kind === 'mobile' && <Link className="ghost linkbtn" to="/services">{t('Usage & SIM')}</Link>}
                  {kind === 'mobile' && <Link className="ghost linkbtn" to="/shop?tab=Top-ups">{t('Add data')}</Link>}
                  {kind === 'broadband' && <Link className="ghost linkbtn" to="/shop?tab=Internet">{t('Upgrade speed')}</Link>}
                  {kind === 'tv' && <Link className="ghost linkbtn" to="/services">{t('Manage package')}</Link>}
                  {sv.state === 'active' && (kind === 'mobile' || kind === 'broadband') && <LineDoctor serviceId={sv.id} />}
                </div>
              </div>
            );
          })}
        </div>
      </section>

      {healthy && recommended}

      {/* money & usage */}
      <section data-testid="home-money">
        <h2>{t('Money & usage')} <Link className="dim small" to="/bills">{t('Billing')} →</Link></h2>
        <div className="cards money">
          <div className="card">
            <h3>{latestBill ? (OPEN_BILL.includes(latestBill.state) ? t('Open bill') : t('Latest bill')) : t('Your bill')}</h3>
            {latestBill ? (
              <p><b>{Number(latestBill.amountDue?.value ?? 0).toFixed(2)} {latestBill.amountDue?.unit}</b> <span className={`state ${latestBill.state}`}>{latestBill.state}</span><br /><span className="dim small">{latestBill.billNo}{latestBill.billDate ? ` · ${String(latestBill.billDate).slice(0, 10)}` : ''}</span></p>
            ) : <p className="dim small">{t('Your first bill comes at the end of your first billing period and covers only the days since your service started.')}</p>}
            {openBills.length > 0 && <Link className="primary linkbtn" to="/bills">{t('Pay')}</Link>}
          </div>
          <div className="card">
            <h3>{t('Data this month')}</h3>
            {dataBucket ? <Meter label={t('Mobile data')} used={dataUsed} allowed={dataAllowed} units={dataBucket.units} warn={nearLimit} /> : <p className="dim small">{t('No data allowance on this account.')}</p>}
            {roaming && roaming.limit && <p className="dim small">{t('Roaming')}: {Number(roaming.accrued?.value || 0)} / {roaming.limit.value} {roaming.limit.unit}</p>}
            {d.program && d.loyalty && <p className="dim small">{t('Points')}: <b>{d.loyalty.balance}</b></p>}
          </div>
        </div>
      </section>

      {/* open work */}
      <section id="open-work" data-testid="home-work">
        <h2>{t('Open work')}</h2>
        {!liveOrders.length && !openTickets.length && !upcoming.length && <p className="ok small">{t('No open work')}</p>}
        <div className="rows">
          {liveOrders.map((o) => {
            const ls = leaves(o.productOrderItem); const done = ls.filter((l) => l.state === 'completed').length;
            return (
              <div className="row" key={o.id}>
                <div><strong>{o.description || ls.map((l) => l.productOffering?.name).filter(Boolean).join(', ') || t('Order')}</strong>
                  <div className="dim small">{t('in progress')}{ls.length > 1 ? ` · ${done} ${t('of')} ${ls.length} ${t('ready')}` : ''} · {dt(o.orderDate)}</div></div>
                <Link className="ghost linkbtn" to="/orders">{t('Track')}</Link>
              </div>
            );
          })}
          {openTickets.map((x) => (
            <div className="row" key={x.id}><div><strong>{x.name}</strong><div className="dim small">{t('support case')} · {x.status}</div></div><Link className="ghost linkbtn" to="/support">{t('View')}</Link></div>
          ))}
          {upcoming.map((ap) => (
            <div className="row" key={ap.id}><div><strong>{ap.description || t('Visit')}</strong><div className="dim small">{dt(ap.validFor?.startDateTime)} · {ap.status}</div></div></div>
          ))}
        </div>
      </section>

      {/* recent activity */}
      <section data-testid="home-activity">
        <h2>{t('Recent activity')} <Link className="dim small" to="/orders">{t('All orders')} →</Link> <Link className="dim small" to="/notifications">{t('Inbox')} →</Link></h2>
        <div className="rows">
          {!activity.length && <p className="dim small">{t('Nothing yet — we will let you know when something happens.')}</p>}
          {activity.map((e, i) => (
            <div className="row" key={i}><div><span className="chip kind">{t(e.kind)}</span> {e.title}</div><span className="dim small">{dt(e.at)}</span></div>
          ))}
        </div>
      </section>

      {/* one suggestion — placed by the situation: right under the services when all is well, below the
          customer's needs when something is open, absent while something critical is open */}
      {!critical && !healthy && recommended}
    </div>
  );
}
