import { useEffect, useState } from 'react';
import { acceptDependent, addFamilyMember, endHouseholdLink, listOfferings, memberProducts,
  myHousehold, orderForDependent, priceIndex, requestHouseholdPayer, setFamilyRole } from '../api.js';
import { tokenClaims } from '../auth.js';
import { fmtPrice, pricesOf } from '../money.js';
import { t } from '../i18n.js';

/**
 * The FAMILY HUB — the one place a household lives, the pattern every big
 * operator converges on (Jio's parent SIM, Verizon's account roles, Orange's
 * co-pilot parent). The payer OWNS the family; members the owner promotes
 * to ADMIN see and manage the whole family too — but the payer stamp stays
 * the owner's: admin is authority, not a wallet. Adult members' own-paid
 * services never show here; a child account's line is fully visible.
 */
export default function Family() {
  const [hh, setHh] = useState(null);
  const [offerings, setOfferings] = useState({});
  const [prices, setPrices] = useState({});
  const [email, setEmail] = useState('');
  const [note, setNote] = useState(null);
  const [error, setError] = useState(null);
  const [reloadTick, setReloadTick] = useState(0);

  const load = () => myHousehold().then(setHh).catch((e) => setError(e.message));
  useEffect(() => {
    load();
    listOfferings().then((all) =>
      setOfferings(Object.fromEntries(all.map((o) => [o.id, o])))).catch(() => {});
    priceIndex().then(setPrices).catch(() => {});
  }, []);

  if (error) return <p className="error">{error}</p>;
  if (!hh) return <p className="dim">{t('Loading…')}</p>;

  const me = tokenClaims().sub;
  const payer = hh.payer;
  const myRole = hh.myRole || null;
  const isAdmin = myRole === 'admin' && payer?.status === 'active';
  const ownDependents = hh.dependents || [];
  const isOwner = ownDependents.length > 0;
  // whose family do I manage: my own dependents as owner, the payer's as admin
  const members = isOwner ? ownDependents : (isAdmin ? (hh.family || []) : []);
  const pending = members.filter((d) => d.status === 'pending');
  const active = members.filter((d) => d.status === 'active' && d.id !== me);

  const act = async (fn, okText) => {
    setNote(null);
    try {
      await fn();
      setNote(okText);
      load();
      setReloadTick((n) => n + 1);
    } catch (e) { setNote(e.message); }
  };

  const roleName = (r) => (r === 'admin' ? t('family admin') : r === 'child' ? t('child') : t('member'));

  return (
    <>
      <h1>👪 {t('Family')}</h1>

      {payer && (
        <section className="lobcard" data-testid="hh-payer">
          <p style={{ margin: '6px 0' }}>
            {payer.status === 'active'
              ? <>{t('Paid for by')} <b>{payer.name || payer.id}</b>
                  {myRole && <span className="state active" data-testid="my-role" style={{ marginLeft: 8 }}>{roleName(myRole)}</span>}
                  {' — '}{t('company-style: their bill, your name on the lines')}.</>
              : <>{t('Waiting for')} <b>{payer.name || payer.id}</b> {t('to accept your request')}…</>}
            <button className="ghost" style={{ marginLeft: 10 }} data-testid="hh-leave"
              onClick={() => act(() => endHouseholdLink(me), t('left the household'))}>
              {t('Leave')}
            </button>
          </p>
          {isAdmin && (
            <p className="dim" style={{ fontSize: 13, margin: '2px 0' }}>
              {t('You are a family admin: you see every member below and can order for them — it bills to the family payer.')}
            </p>
          )}
        </section>
      )}

      {!payer && !isOwner && (
        <section className="lobcard">
          <p className="dim" style={{ fontSize: 13 }}>
            {t('One person, many payers: ask someone to pay for your subscriptions, or add family members below — you become the family payer.')}
          </p>
        </section>
      )}

      {!payer && (
        <section className="lobcard">
          <h2>{t('Someone pays for you?')}</h2>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', margin: '6px 0' }}>
            <input placeholder={t('who pays for you? their email')} value={email}
              data-testid="hh-request-email" style={{ flex: 1, minWidth: 220 }}
              onChange={(e) => setEmail(e.target.value)} />
            <button className="ghost" data-testid="hh-request" disabled={!email.trim()}
              onClick={() => act(() => requestHouseholdPayer(email.trim()), t('request sent — pending their consent'))}>
              {t('Ask them to pay')}
            </button>
          </div>
        </section>
      )}

      {pending.map((d) => (
        <section className="lobcard" key={d.id} data-testid={`hh-dependent-${d.id}`}>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
            <b>{d.givenName} {d.familyName}</b>
            <span className="dim">{t('asks you to pay for them')}</span>
            {isOwner ? (
              <button className="ghost" data-testid="hh-accept"
                onClick={() => act(() => acceptDependent(d.id), t('accepted — their orders can bill to you now'))}>
                {t('Accept')}
              </button>
            ) : <span className="dim">{t('only the payer can accept')}</span>}
          </div>
        </section>
      ))}

      {active.map((d) => (
        <MemberCard key={d.id} member={d} offerings={offerings} prices={prices}
          canManageRoles={isOwner} canEndLink={isOwner} act={act} reloadTick={reloadTick} />
      ))}

      {(isOwner || isAdmin) && !active.length && !pending.length && (
        <p className="dim">{t('No family members yet.')}</p>
      )}

      {!payer && (
        <section className="lobcard">
          <details>
            <summary className="dim" style={{ cursor: 'pointer', fontSize: 13 }}>
              ➕ {t('Add a family member (creates their own sign-in)')}
            </summary>
            <AddFamilyMember onAdded={load} />
          </details>
        </section>
      )}

      {note && <p className="dim" data-testid="hh-note" style={{ fontSize: 12.5 }}>{note}</p>}
    </>
  );
}

/**
 * One family member: role chip, the services the FAMILY pays for on their
 * line (their own purchases stay theirs — paying is not surveillance),
 * order-for, and — for the owner alone, the Verizon rule — role management.
 */
function MemberCard({ member, offerings, prices, canManageRoles, canEndLink, act, reloadTick }) {
  const [paid, setPaid] = useState(null);
  const [pick, setPick] = useState('');

  useEffect(() => {
    memberProducts(member.id).then(setPaid).catch(() => setPaid([]));
  }, [member.id, reloadTick]);

  const plans = Object.values(offerings)
    .filter((o) => ((o.category || [])[0] || {}).name === 'Mobile plans' && !o.isBundle);
  const monthlyOf = (p) => {
    const offering = offerings[p.productOffering?.id];
    return offering ? pricesOf(offering, prices).find((x) => x.priceType === 'recurring') : null;
  };
  const isChild = member.role === 'child';

  return (
    <section className="lobcard" data-testid={`fam-member-${member.id}`}>
      <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
        <h2 style={{ margin: 0 }}>{member.givenName} {member.familyName}</h2>
        <span className="state active" data-testid="role-chip">{member.role === 'admin'
          ? t('family admin') : isChild ? t('child') : t('member')}</span>
        <a href={`/shop/family/${member.id}`} target="_blank" rel="noreferrer"
          className="promolink" data-testid="family-open">
          {t('Open their page')} ↗
        </a>
      </div>
      {paid == null ? <p className="dim">{t('Loading…')}</p> : (
        <>
          {paid.map((p) => (
            <div className="row" key={p.id}>
              <strong>{p.name}</strong>
              {monthlyOf(p) && <span className="dim">{fmtPrice(monthlyOf(p))}</span>}
              <span className={`state ${p.status}`}>{p.status}</span>
              <span className="dim" style={{ fontSize: 12 }}>{t('billed to the family payer')}</span>
            </div>
          ))}
          {!paid.length && <p className="dim" style={{ fontSize: 13 }}>
            {t('Nothing on the family bill for them yet.')}</p>}
          {!isChild && (
            <p className="dim" style={{ fontSize: 12.5, margin: '4px 0' }}>
              {t('What they buy with their own money stays on their own page — paying for someone is not watching them.')}
            </p>
          )}
        </>
      )}
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginTop: 6 }}>
        <select value={pick} data-testid="hh-order-select"
          onChange={(e) => setPick(e.target.value)}>
          <option value="" disabled>{t('order a plan for them…')}</option>
          {plans.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
        </select>
        <button className="ghost" data-testid="hh-order" disabled={!pick}
          onClick={() => act(() => orderForDependent(offerings[pick], member.id),
            t('ordered — it bills to the family payer, attributed to them'))}>
          {t('Order')}
        </button>
        {canManageRoles && !isChild && (member.role === 'admin' ? (
          <button className="ghost" data-testid={`fam-demote-${member.id}`}
            onClick={() => act(() => setFamilyRole(member.id, 'member'), t('no longer a family admin'))}>
            {t('Remove admin')}
          </button>
        ) : (
          <button className="ghost" data-testid={`fam-promote-${member.id}`}
            onClick={() => act(() => setFamilyRole(member.id, 'admin'),
              t('they are a family admin now — they can manage the family too'))}>
            {t('Make admin')}
          </button>
        ))}
        {canEndLink && (
          <button className="ghost danger" data-testid="hh-stop"
            onClick={() => act(() => endHouseholdLink(member.id), t('stopped paying'))}>
            {t('Stop paying')}
          </button>
        )}
      </div>
    </section>
  );
}

/** Child accounts: the payer creates the kid's login + party in one go —
 * consent is implicit when the payer IS the creator (a minor can't consent).
 * The temporary password shows exactly once, for hand-over to the kid's
 * phone: their own sign-in, their own My page, your bill. */
function AddFamilyMember({ onAdded }) {
  const [given, setGiven] = useState('');
  const [family, setFamily] = useState('');
  const [email, setEmail] = useState('');
  const [made, setMade] = useState(null);
  const [err, setErr] = useState(null);

  async function add() {
    setErr(null);
    try {
      const result = await addFamilyMember(given.trim(), family.trim(), email.trim());
      setMade(result);
      setGiven(''); setFamily(''); setEmail('');
      if (onAdded) onAdded();
    } catch (e) { setErr(e.message); }
  }

  if (made) {
    return (
      <p data-testid="hh-credentials" style={{ fontSize: 13 }}>
        ✓ {t('Created — they sign in with')}{' '}
        <b style={{ fontFamily: 'ui-monospace, monospace' }}>{made.email} / {made.temporaryPassword}</b>
        <br /><span className="dim">{t('Shown once — hand it over to their phone.')}</span>
      </p>
    );
  }
  return (
    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 8 }}>
      <input placeholder={t('First name')} value={given} data-testid="hh-add-given"
        onChange={(e) => setGiven(e.target.value)} style={{ width: 120 }} />
      <input placeholder={t('Last name')} value={family} data-testid="hh-add-family"
        onChange={(e) => setFamily(e.target.value)} style={{ width: 120 }} />
      <input placeholder={t('Email')} value={email} data-testid="hh-add-email"
        onChange={(e) => setEmail(e.target.value)} style={{ flex: 1, minWidth: 180 }} />
      <button className="ghost" data-testid="hh-add" disabled={!given.trim() || !email.trim()}
        onClick={add}>{t('Create their account')}</button>
      {err && <span className="error" style={{ fontSize: 12.5 }}>{err}</span>}
    </div>
  );
}
