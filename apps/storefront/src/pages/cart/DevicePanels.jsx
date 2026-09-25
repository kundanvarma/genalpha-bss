/*
 * The device panels of the cart: how a handset is paid for (in full, operator
 * instalments, pay-later — every option with its monthly AND total cost) and
 * the trade-in offer. Extracted from Cart.jsx, behaviour unchanged; the cart
 * page renders them and owns the device plan they edit.
 */
import React, { useState, useEffect } from 'react';
import { financingQuote, quoteTradeIn } from '../../api.js';
import { t } from '../../i18n.js';

/** The device plan a fresh trade-in or financing pick starts from. */
export function freshDevicePlan(deviceLine, devicePrice) {
  return {
    offeringId: deviceLine.offeringId,
    deviceName: deviceLine.name,
    principal: { value: devicePrice.value, unit: devicePrice.unit },
    financing: 'FULL',
    termMonths: 24,
    monthlyAmount: null,
    totalCostOfOwnership: devicePrice.value,
  };
}

const TERM_CHOICES = [12, 24, 36];

/**
 * Pay in full | monthly instalments (operator) | pay later (BNPL) — every
 * option states the monthly cost AND the total cost over the term. Total-cost
 * transparency is deliberate: the price of spreading a phone is never fine
 * print. Server quotes when signed in; honest local math otherwise.
 */
export function FinancingChooser({ deviceLine, devicePrice, plan, signedIn, onPlan }) {
  const financing = plan?.financing || 'FULL';
  const termMonths = plan?.termMonths || 24;
  const [quotes, setQuotes] = useState({}); // `${model}:${term}` -> quote

  const localQuote = (model, term) => ({
    financingModel: model,
    monthlyAmount: Math.round((devicePrice.value / term) * 100) / 100,
    totalCostOfOwnership: devicePrice.value,
  });
  useEffect(() => {
    if (!signedIn) return;
    for (const model of ['OPERATOR_BOOK', 'BNPL']) {
      const key = `${model}:${termMonths}`;
      if (quotes[key]) continue;
      financingQuote(devicePrice.value, termMonths, model)
        .then((q) => setQuotes((prev) => ({ ...prev, [key]: q })))
        .catch(() => {}); // local math already carries the display
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signedIn, termMonths, devicePrice.value]);

  const quoteFor = (model) => quotes[`${model}:${termMonths}`] || localQuote(model, termMonths);
  const pick = (model) => {
    if (model === 'FULL') {
      onPlan({ ...(plan || freshDevicePlan(deviceLine, devicePrice)),
        financing: 'FULL', monthlyAmount: null, totalCostOfOwnership: devicePrice.value });
      return;
    }
    const q = quoteFor(model);
    onPlan({ ...(plan || freshDevicePlan(deviceLine, devicePrice)),
      financing: model, termMonths,
      monthlyAmount: Number(q.monthlyAmount),
      totalCostOfOwnership: Number(q.totalCostOfOwnership) });
  };
  const setTerm = (term) => {
    const next = { ...(plan || freshDevicePlan(deviceLine, devicePrice)), termMonths: term };
    if (next.financing !== 'FULL') {
      const q = quotes[`${next.financing}:${term}`] || localQuote(next.financing, term);
      next.monthlyAmount = Number(q.monthlyAmount);
      next.totalCostOfOwnership = Number(q.totalCostOfOwnership);
    }
    onPlan(next);
  };

  const unit = devicePrice.unit;
  const fmt = (v) => `${Number(v).toFixed(2)} ${unit}`;
  const opQuote = quoteFor('OPERATOR_BOOK');
  const bnplQuote = quoteFor('BNPL');
  return (
    <div className="finchooser" data-testid="financing-chooser">
      <p className="dim small">How would you like to pay for the phone?</p>
      <div className="simopts">
        <button type="button" data-testid="fin-full"
                className={`simopt ${financing === 'FULL' ? 'on' : ''}`}
                onClick={() => pick('FULL')}>
          <span className="simopt-t">💳 Pay in full</span>
          <span className="simopt-d">{fmt(devicePrice.value)} today — total cost {fmt(devicePrice.value)}</span>
        </button>
        <button type="button" data-testid="fin-installments"
                className={`simopt ${financing === 'OPERATOR_BOOK' ? 'on' : ''}`}
                onClick={() => pick('OPERATOR_BOOK')}>
          <span className="simopt-t">📅 Monthly instalments</span>
          <span className="simopt-d">
            {fmt(opQuote.monthlyAmount)}/mo × {termMonths} on your bill — total cost {fmt(opQuote.totalCostOfOwnership)}, nothing for the phone today
          </span>
        </button>
        <button type="button" data-testid="fin-bnpl"
                className={`simopt ${financing === 'BNPL' ? 'on' : ''}`}
                onClick={() => pick('BNPL')}>
          <span className="simopt-t">🕐 Pay later</span>
          <span className="simopt-d">
            {fmt(bnplQuote.monthlyAmount)}/mo × {termMonths} with the pay-later provider — total cost {fmt(bnplQuote.totalCostOfOwnership)}
          </span>
        </button>
      </div>
      {financing !== 'FULL' && (
        <p className="small dim finterm">
          Over{' '}
          <select data-testid="fin-term" value={termMonths}
                  onChange={(e) => setTerm(Number(e.target.value))}>
            {TERM_CHOICES.map((m) => <option key={m} value={m}>{m} months</option>)}
          </select>
          {' '}— the agreement appears under <b>My devices</b> after checkout.
          {!signedIn && ' Sign in at checkout to finalize the instalment agreement.'}
        </p>
      )}
    </div>
  );
}

// The guided assessment — the same defects the backend prices, no black box.
const TRADE_IN_DEFECTS = [
  { name: 'screenCracked', label: 'The screen is cracked' },
  { name: 'backCracked', label: 'The back is cracked' },
  { name: 'batteryWorn', label: 'The battery drains fast' },
  { name: 'notPoweringOn', label: 'It does not power on' },
];

/**
 * "Trade in your old phone": IMEI + condition answers → a live estimate that
 * shows as a cart credit line. The valuation id rides the order so the
 * backend links them; the credit itself pays out after grading.
 */
export function TradeInPanel({ deviceLine, devicePrice, plan, signedIn, onTradeIn }) {
  const [open, setOpen] = useState(Boolean(plan?.tradeIn));
  const [model, setModel] = useState('');
  const [imei, setImei] = useState('');
  const [age, setAge] = useState('12');
  const [defects, setDefects] = useState({});
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState(null);
  const [err, setErr] = useState(null);
  const tradeIn = plan?.tradeIn || null;

  async function estimate() {
    setBusy(true); setErr(null); setNote(null);
    try {
      const v = await quoteTradeIn({
        imei: imei.trim(),
        deviceRef: model.trim(),
        conditionAnswers: { ageMonths: Number(age), ...defects },
      });
      onTradeIn({ valuationId: v.id, estimatedValue: Number(v.estimatedValue),
        currency: v.currency, deviceRef: v.deviceRef });
      setNote(v.note || null);
    } catch (e) {
      setErr(e.message);
    }
    setBusy(false);
  }

  if (!signedIn) {
    return (
      <p className="dim small" data-testid="trade-in-signin">
        ♻️ Have an old phone? Sign in at checkout to trade it in for credit.
      </p>
    );
  }
  if (!open) {
    return (
      <p className="small">
        <button type="button" className="linkbtn" data-testid="trade-in-toggle"
                onClick={() => setOpen(true)}>
          ♻️ Trade in your old phone — get its value as credit
        </button>
      </p>
    );
  }
  return (
    <div className="tradein" data-testid="trade-in-panel">
      <h3>♻️ Trade in your old phone</h3>
      {tradeIn ? (
        <>
          <p className="small" data-testid="trade-in-estimate">
            ✓ Estimated value for your {tradeIn.deviceRef}:{' '}
            <strong style={{ color: 'var(--teal)' }}>
              {Number(tradeIn.estimatedValue).toFixed(2)} {tradeIn.currency}
            </strong>
            {' '}— applied to your cart. Mail the phone in after checkout; the final value
            follows the grading, and you approve any change.
          </p>
          {note && <p className="dim small">{note}</p>}
          <button type="button" className="ghost" data-testid="trade-in-remove"
                  onClick={() => { onTradeIn(null); setNote(null); }}>
            Remove trade-in
          </button>
        </>
      ) : (
        <>
          <p className="dim small">
            Answer honestly — the estimate is provisional until our partner checks the phone,
            and you approve any revised value before it stands.
          </p>
          <div className="addressgrid">
            <label className="charfield"><span>Which phone is it?</span>
              <input data-testid="trade-in-model" value={model} placeholder="e.g. Galaxy S22"
                     onChange={(e) => setModel(e.target.value)} /></label>
            <label className="charfield"><span>IMEI (dial *#06#)</span>
              <input data-testid="trade-in-imei" value={imei} inputMode="numeric"
                     placeholder="15 digits"
                     onChange={(e) => setImei(e.target.value.replace(/[^\d]/g, ''))} /></label>
            <label className="charfield"><span>How old is it?</span>
              <select data-testid="trade-in-age" value={age} onChange={(e) => setAge(e.target.value)}>
                <option value="6">Under a year</option>
                <option value="12">1–2 years</option>
                <option value="24">2–3 years</option>
                <option value="36">Over 3 years</option>
              </select></label>
          </div>
          <div className="tradein-defects">
            {TRADE_IN_DEFECTS.map((d) => (
              <label key={d.name} className="small">
                <input type="checkbox" data-testid={`trade-in-${d.name}`}
                       checked={Boolean(defects[d.name])}
                       onChange={(e) => setDefects({ ...defects, [d.name]: e.target.checked })} />
                {' '}{d.label}
              </label>
            ))}
          </div>
          <button type="button" className="ghost" data-testid="trade-in-quote"
                  disabled={busy || !model.trim() || imei.trim().length < 8}
                  onClick={estimate}>
            {busy ? 'Valuing…' : 'Get my estimate'}
          </button>
          {' '}
          <button type="button" className="linkbtn small" onClick={() => setOpen(false)}>Never mind</button>
          {err && <p className="error small" data-testid="trade-in-error">{err}</p>}
        </>
      )}
    </div>
  );
}
