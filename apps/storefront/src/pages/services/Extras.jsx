import { Link } from 'react-router-dom';
import { fmtPrice } from '../../money.js';
import { giftData, myReferral, quickOrder, redeemReferral } from '../../api.js';
import { t } from '../../i18n.js';
import { tokenClaims } from '../../auth.js';
import { useEffect, useState } from 'react';

/** One tap, more data now: buys the top-up and the meter grows this month.
 * A held order is the ask-to-buy path — the family admin decides. */
export function TopUp({ offering, price, onBought }) {
  const [state, setState] = useState(null); // null | 'busy' | 'done' | 'held' | error
  // a BOOST PASS is a top-up that buys network priority, not data
  const isBoost = /boost|priority/i.test(`${offering.name || ''} ${offering.description || ''}`);
  async function buy() {
    setState('busy');
    try {
      const order = await quickOrder(offering);
      if (order.state === 'held') {
        setState('held');
        return;
      }
      setState('done');
      // the boost lands via the event stream; give it a beat then refresh
      setTimeout(onBought, 2500);
      setTimeout(onBought, 6000);
      // a boost pass lands on the LINE (slice at the core, stamp on the record) — give it longer
      if (isBoost) { setTimeout(onBought, 12000); setTimeout(onBought, 20000); }
    } catch (e) { setState(e.message); }
  }
  return (
    <p style={{ margin: '8px 0 0' }}>
      <button className="ghost" data-testid={`topup-${offering.id}`} disabled={state === 'busy'} onClick={buy}>
        {state === 'busy' ? t('Buying…') : `${isBoost ? '⚡ ' : ''}${offering.name}${price ? ` — ${fmtPrice(price)}` : ''}`}
      </button>
      {isBoost && !state && offering.description && <span className="dim small"> {offering.description.split('.')[0]}.</span>}
      {state === 'done' && <span className="dim" data-testid="topup-done"> ✓ {isBoost ? t('priority network is on for your line — see the badge above') : t("added to this month's allowance")}</span>}
      {state === 'held' && <span className="dim" data-testid="topup-held">
        {' '}🔔 {t('sent to your family admin for approval — you\'ll hear the moment they decide')}</span>}
      {state && state !== 'done' && state !== 'held' && state !== 'busy' && <span className="error"> {state}</span>}
    </p>
  );
}

/** GIFT DATA (the gifting move): hand whole-GB chunks of your remaining data to
 * a family member — or straight to a PHONE NUMBER, when the plan's
 * giftScope reaches network-wide. Their meter grows, yours shrinks —
 * generosity, not money, so no approvals. */
export function GiftData({ hh, onDone }) {
  const [to, setTo] = useState('');
  const [phone, setPhone] = useState('');
  const [amount, setAmount] = useState('');
  const [note, setNote] = useState(null);

  const people = [];
  if (hh?.payer && hh.payer.status === 'active') {
    people.push({ id: hh.payer.id, name: hh.payer.name || t('your payer') });
  }
  const me = tokenClaims().sub;
  for (const d of [...(hh?.dependents || []), ...(hh?.family || [])]) {
    if (d.status === 'active' && d.id !== me && !people.some((p) => p.id === d.id)) {
      people.push({ id: d.id, name: `${d.givenName} ${d.familyName}` });
    }
  }

  async function send() {
    setNote(null);
    try {
      const gift = await giftData(phone.trim()
        ? { receiverPhone: phone.trim() } : { receiverId: to }, Number(amount));
      setNote(`✓ ${t('gifted')} ${gift.amount} GB ${t('to')} ${gift.receiver.name}`);
      setAmount(''); setPhone('');
      setTimeout(onDone, 2500);
    } catch (e) { setNote(e.message); }
  }

  return (
    <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginTop: 8 }}>
      <span className="dim" style={{ fontSize: 13 }}>🎁 {t('Gift data')}</span>
      {people.length > 0 && (
        <select value={to} data-testid="gift-select"
          onChange={(e) => { setTo(e.target.value); setPhone(''); }}>
          <option value="" disabled>{t('to a family member…')}</option>
          {people.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </select>
      )}
      <input style={{ width: '12em' }} inputMode="tel"
        placeholder={people.length ? t('…or a phone number') : t('their phone number')}
        data-testid="gift-phone" value={phone}
        onChange={(e) => { setPhone(e.target.value.replace(/[^0-9+ -]/g, '')); if (e.target.value) setTo(''); }} />
      <input style={{ width: '4.5em' }} inputMode="numeric" placeholder="GB"
        data-testid="gift-amount" value={amount}
        onChange={(e) => setAmount(e.target.value.replace(/\D/g, ''))} />
      <button className="ghost" data-testid="gift-send" disabled={(!to && !phone.trim()) || !amount}
        onClick={send}>{t('Send')}</button>
      {note && <span className="dim" data-testid="gift-note" style={{ fontSize: 12.5 }}>{note}</span>}
    </div>
  );
}

/** G1 — member-get-member: my code, my tally, and the redeem field. The
 *  reward pays BOTH sides in data when the joiner's first order completes. */
export function ReferralCard() {
  const [ref, setRef] = useState(null);
  const [code, setCode] = useState('');
  const [msg, setMsg] = useState(null);
  useEffect(() => { myReferral().then(setRef).catch(() => {}); }, []);
  if (!ref) return null;
  const share = `${window.location.origin}/shop/?ref=${ref.code}`;
  return (
    <section className="card" data-testid="referral-card" style={{ padding: '14px 18px', marginBottom: 14 }}>
      <h2 style={{ marginTop: 0 }}>{t('Invite a friend')}</h2>
      <p style={{ margin: '4px 0 10px' }}>
        {t('You each get')} <b>{ref.rewardGb} GB</b> {t('when they place their first order.')}
      </p>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <b data-testid="referral-code" style={{ fontSize: '1.3rem', letterSpacing: '0.08em' }}>{ref.code}</b>
        <button className="ghost" data-testid="referral-copy"
          onClick={() => { navigator.clipboard?.writeText(share); setMsg(t('Link copied!')); }}>
          {t('Copy invite link')}</button>
        <span className="dim">{ref.rewarded} {t('friends joined')}{ref.pending > 0 ? ` · ${ref.pending} ${t('on the way')}` : ''}</span>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 10, flexWrap: 'wrap' }}>
        <input data-testid="referral-input" placeholder={t('Got a code? Enter it here')}
          value={code} onChange={(e) => setCode(e.target.value)} style={{ width: '12rem' }} />
        <button className="ghost" data-testid="referral-redeem" disabled={!code.trim()}
          onClick={async () => {
            try { const r = await redeemReferral(code.trim()); setMsg(r.note || t('Code accepted!')); }
            catch (e) { setMsg(String(e.message || e)); }
          }}>{t('Redeem')}</button>
        {msg && <span data-testid="referral-msg" className="dim">{msg}</span>}
      </div>
    </section>
  );
}

