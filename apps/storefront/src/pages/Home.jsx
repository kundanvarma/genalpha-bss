import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { t } from '../i18n.js';
import { tokenClaims } from '../auth.js';
import { myProducts, myActiveServices, myUsage, myBills, myOrders, myTickets, myAppointments, myNotifications,
  myCollectionCase, forYou, myRecommendations, listOfferings, loyaltyProgram, myLoyalty, mySpendPolicy,
  myHomeContext, resumeMyService } from '../api.js';
import { LineDoctor } from './Services.jsx';

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
  const [d, setD] = useState({ products: [], services: [], usage: [], bills: [], orders: [], tickets: [], appointments: [], notifications: [], ccase: null, offerings: {}, personal: null, recIds: [], program: null, loyalty: null, spend: [] });
  const [ctx, setCtx] = useState(null); // the ontology's reading; null = reading, {} = unavailable
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const load = () => {
    const soft = (p, fallback) => p.catch(() => fallback);
    Promise.all([
      soft(myProducts(), []), soft(myActiveServices(), []), soft(myUsage(), []), soft(myBills(), []), soft(myOrders(), []),
      soft(myTickets(), []), soft(myAppointments(), []), soft(myNotifications(), []), soft(myCollectionCase(), null),
      soft(listOfferings(), []), soft(forYou(), null), soft(myRecommendations(), []), soft(loyaltyProgram(), null), soft(myLoyalty(), null), soft(mySpendPolicy(), []),
    ]).then(([products, services, usage, bills, orders, tickets, appointments, notifications, ccase, offerings, personal, recs, program, loyalty, spend]) => {
      const index = Object.fromEntries((Array.isArray(offerings) ? offerings : []).map((o) => [o.id, o]));
      const recIds = personal && personal.items?.length ? personal.items.map((i) => i.id) : (recs[0]?.recommendationItem?.map((i) => i.offering.id) || []);
      setD({ products, services: Array.isArray(services) ? services : [], usage: Array.isArray(usage) ? usage : [], bills, orders, tickets: Array.isArray(tickets) ? tickets : [], appointments: Array.isArray(appointments) ? appointments : [], notifications: Array.isArray(notifications) ? notifications : [], ccase, offerings: index, personal, recIds, program, loyalty, spend: Array.isArray(spend) ? spend : [] });
    });
    setCtx(null);
    myHomeContext(me).then((c) => setCtx(c || {})).catch(() => setCtx({}));
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

  // the attention list: only what needs the customer, each with its next step
  const attention = [];
  for (const inc of incidents) {
    const sv = active.find((s) => (inc.says || '').includes(s.id));
    attention.push({ kind: 'incident', level: 'warn', text: `${t('We know about a problem on your line')}${sv && numberOf(sv) ? ' ' + numberOf(sv) : ''}. ${t('Our network team is on it — you do not need to do anything.')}`, to: '/support', cta: t('Check my line') });
  }
  for (const sv of paused) attention.push({ kind: 'paused', level: 'warn', text: `${sv.name}${numberOf(sv) ? ' ' + numberOf(sv) : ''} ${t('is paused — nothing is charged and nothing connects until you resume it.')}`, action: 'resume', service: sv, cta: t('Resume') });
  if (caseOpen) attention.push({ kind: 'overdue', level: 'danger', text: `${t('An amount is overdue')}: ${Number(d.ccase.overdueBalance?.value || 0).toFixed(2)} ${d.ccase.overdueBalance?.unit || ''}. ${t('Please settle it to keep your services running.')}`, to: '/bills', cta: t('Pay now') });
  else if (openBills.length) attention.push({ kind: 'bill', level: 'info', text: `${t('A bill is open')}: ${openBills[0].billNo} — ${Number(openBills[0].amountDue.value).toFixed(2)} ${openBills[0].amountDue.unit}.`, to: '/bills', cta: t('See the bill') });
  if (nearLimit) attention.push({ kind: 'data', level: 'info', text: `${t('Your data is nearly used up')}: ${Math.max(0, dataAllowed - dataUsed)} ${dataBucket.units} ${t('left of')} ${dataAllowed}.`, to: '/shop?tab=Top-ups', cta: t('Buy extra data') });
  for (const o of liveOrders.slice(0, 1)) attention.push({ kind: 'order', level: 'info', text: `${t('Your order is in progress')}: ${o.description || leaves(o.productOrderItem).map((l) => l.productOffering?.name).filter(Boolean).join(', ')}.`, href: '#open-work', cta: t('Track it') });
  for (const x of openTickets.slice(0, 1)) attention.push({ kind: 'case', level: 'info', text: `${t('Your support case is')} ${x.status}: ${x.name}.`, to: '/support', cta: t('View case') });

  const activity = useMemo(() => {
    const ev = [];
    for (const o of d.orders) ev.push({ at: o.orderDate, kind: 'order', title: `${t('Order')} ${o.state}: ${o.description || leaves(o.productOrderItem).map((l) => l.productOffering?.name).filter(Boolean).join(', ') || ''}` });
    for (const m of d.notifications) ev.push({ at: m.sendTime, kind: 'message', title: m.subject });
    for (const x of d.tickets) ev.push({ at: x.creationDate, kind: 'case', title: `${t('Support case')} ${x.status}: ${x.name}` });
    return ev.filter((e) => e.at).sort((a, b) => new Date(b.at) - new Date(a.at)).slice(0, 5);
  }, [d.orders, d.notifications, d.tickets]);

  const picks = d.recIds.map((id) => d.offerings[id]).filter(Boolean).slice(0, 3);
  const cards = active.slice(0, 6); // a household has a handful; the full list lives under Services
  const given = claims.given_name || claims.name || '';

  const resume = async (sv) => {
    setBusy(true); setError(null);
    try { await resumeMyService(sv.id); load(); } catch (e) { setError(e.message); }
    setBusy(false);
  };

  return (
    <div className="home" data-testid="home">
      <section className="hero home-hero" data-testid="home-greeting">
        <h1>{t(partOfDay())}{given ? `, ${given}` : ''}</h1>
        <p data-testid="home-health" className={attention.length ? 'warn' : 'ok'}>
          {ctx === null && !attention.length ? t('Reading your services…')
            : attention.length ? `${attention.length} ${attention.length === 1 ? t('thing needs your attention') : t('things need your attention')}`
            : t('Everything looks good')}
        </p>
      </section>
      {error && <p className="error">{error}</p>}

      {/* attention: only when relevant */}
      <section className="home-attention" data-testid="home-attention" aria-live="polite">
        {ctx === null && <p className="dim small">{t('Reading your services…')}</p>}
        {attention.map((a) => (
          <div key={a.kind + (a.service?.id || '')} className={`callout ${a.level}`} data-testid={`attention-${a.kind}`}>
            <span>{a.text}</span>
            {a.action === 'resume' && <button className="primary" disabled={busy} onClick={() => resume(a.service)}>{a.cta}</button>}
            {a.to && <Link className="primary linkbtn" to={a.to}>{a.cta}</Link>}
            {a.href && <a className="ghost linkbtn" href={a.href}>{a.cta}</a>}
          </div>
        ))}
      </section>

      {/* my services */}
      <section data-testid="home-services">
        <h2>{t('My services')} <Link className="dim small" to="/services">{active.length > 6 ? `${t('All')} ${active.length} →` : `${t('Manage')} →`}</Link></h2>
        {!cards.length && <p className="dim">{t('Nothing active yet — your plan appears here once an order completes.')} <Link to="/shop">{t('Shop')} →</Link></p>}
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
                  {kind === 'mobile' && dataBucket && <span className={nearLimit ? 'error' : ''}>{Math.max(0, dataAllowed - dataUsed)} {dataBucket.units} {t('data left')}</span>}
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

      {/* one suggestion, below the customer's needs */}
      <section data-testid="home-recommended">
        <h2>{t('Recommended for you')} <Link className="dim small" to="/shop">{t('Shop')} →</Link></h2>
        {d.personal?.caption && <p className="dim small" data-testid="foryou-caption">✨ {d.personal.caption} <span className="dim">({t('why this')})</span></p>}
        {!picks.length && <p className="dim small">{t('Nothing to suggest right now.')}</p>}
        <div className="cards" data-testid="recommended">
          {picks.map((o) => (
            <Link className="card" key={o.id} to={`/offering/${o.id}`}>
              <h2>{o.name}</h2>
              {o.description && <p className="dim small">{o.description}</p>}
            </Link>
          ))}
        </div>
      </section>
    </div>
  );
}
