import React, { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { availabilityFor, checkQualification, getOffering, getSpec, myParty, previewPrice, priceIndex, searchTimeSlots } from '../api.js';
import { beginLogin, isSignedIn } from '../auth.js';
import { CART_EVENT, cartLines, ensureInCart, markCartCheckedOut, removeLine, setLineCharacteristics, setQuantity } from '../cart.js';
import { ADDRESS_FIELDS, addressOf, isComplete, loadDraft, saveDraft } from '../address.js';
import { dueNow, loadSlotDraft, performCheckout, qualificationItems, saveSlotDraft } from '../checkout.js';
import { checkPromotion, savePaymentMethod } from '../api.js';
import { monthlyTotal, pricesOf } from '../money.js';
import { setPendingCheckout } from '../pending.js';
import { t } from '../i18n.js';

export default function Cart() {
  const navigate = useNavigate();
  const [lines, setLines] = useState(null);
  const [offerings, setOfferings] = useState({}); // offering id -> full offering
  const [prices, setPrices] = useState({});
  const [physical, setPhysical] = useState({});   // offering id -> boolean (stock-managed)
  const [address, setAddress] = useState(loadDraft());
  const [card, setCard] = useState({ cardNumber: '', expiry: '', cvc: '' });
  const [saveCard, setSaveCard] = useState(false);
  const [serviceability, setServiceability] = useState(null); // TMF679 check result
  const [slots, setSlots] = useState(null);
  const [promoInput, setPromoInput] = useState('');
  // Survives the sign-in redirect, like the address draft.
  const [promo, setPromo] = useState(() => {
    try { return JSON.parse(localStorage.getItem('bss.shop.promo')) || null; } catch { return null; }
  });
  const [promoError, setPromoError] = useState(null);
  const [slot, setSlot] = useState(loadSlotDraft());
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [keepNumber, setKeepNumber] = useState({ on: false, number: '', currentProvider: '' });

  useEffect(() => {
    const refresh = () => { cartLines().then(setLines).catch((e) => setError(e.message)); };
    refresh();
    window.addEventListener(CART_EVENT, refresh);
    return () => window.removeEventListener(CART_EVENT, refresh);
  }, []);

  useEffect(() => {
    if (!lines) return;
    const allIds = [...new Set(lines.flatMap((l) => [l.offeringId, ...(l.selections || []).map((s) => s.offeringId)]))];
    const newIds = allIds.filter((id) => !offerings[id]);
    Promise.all([
      newIds.length ? Promise.all(newIds.map(getOffering)) : [],
      priceIndex(),
      Promise.all(allIds.filter((id) => physical[id] === undefined)
        .map(async (id) => [id, (await availabilityFor(id)) != null])),
    ])
      .then(([loaded, p, phys]) => {
        setPrices(p);
        if (loaded.length) {
          setOfferings((o) => ({ ...o, ...Object.fromEntries(loaded.map((f) => [f.id, f])) }));
        }
        if (phys.length) {
          setPhysical((prev) => ({ ...prev, ...Object.fromEntries(phys) }));
        }
      })
      .catch((e) => setError(e.message));
  }, [lines]);

  // Prefill from the saved party address once signed in (draft wins if typed).
  useEffect(() => {
    if (!isSignedIn() || isComplete(loadDraft())) return;
    myParty().then((party) => {
      const saved = addressOf(party);
      if (saved) {
        setAddress(saved);
        saveDraft(saved);
      }
    }).catch(() => {});
  }, []);

  // Serviceability: re-check whenever the postcode or the cart changes. The
  // result is tagged with the postcode it was computed for so a stale answer
  // never judges a newer address.
  useEffect(() => {
    const ids = Object.keys(offerings);
    if (!lines || !lines.length || !ids.length) return;
    const postCode = address.postCode;
    checkQualification(qualificationItems(lines, offerings),
        { postCode, city: address.city, country: address.country })
      .then((check) => setServiceability({ check, postCode }))
      .catch(() => setServiceability(null));
  }, [lines, offerings, address.postCode]);

  const qualificationItemsResult = serviceability?.check?.productOfferingQualificationItem || [];
  const needsInstall = qualificationItemsResult.some((i) => i.serviceabilityGated);
  const current = serviceability?.postCode === address.postCode;
  const unqualifiedItem = isComplete(address) && current
    ? qualificationItemsResult.find((i) => i.qualificationItemResult === 'unqualified')
    : null;

  // Installer slots appear once an install is needed.
  useEffect(() => {
    if (!needsInstall || slots) return;
    searchTimeSlots()
      .then((result) => setSlots((result.availableTimeSlot || []).slice(0, 6)))
      .catch((e) => setError(e.message));
  }, [needsInstall]);

  // Dynamic pricing preview: what the operator's enabled pricing rules do to
  // this cart's monthly total — the same rules the bill will apply, shown
  // before checkout instead of after. Fail-soft (guests/outage: no preview).
  // specs for the cart's offerings: configurable characteristics (a device's
  // colour) are picked HERE when a line arrived unconfigured (deal adds)
  const [specsById, setSpecsById] = useState({});
  useEffect(() => {
    const wanted = [...new Set(Object.values(offerings)
      .map((o) => o.productSpecification?.id).filter(Boolean))]
      .filter((sid) => !specsById[sid]);
    if (!wanted.length) return;
    Promise.all(wanted.map((sid) => getSpec(sid).catch(() => null)))
      .then((loaded) => setSpecsById((prev) => ({ ...prev,
        ...Object.fromEntries(loaded.filter(Boolean).map((sp) => [sp.id, sp])) })));
  }, [offerings]);

  // deals that touch a line in this cart but whose partner is missing —
  // the cart offers to complete them (one idempotent click)
  const [dealHints, setDealHints] = useState([]);
  useEffect(() => {
    if (!lines || !lines.length) { setDealHints([]); return; }
    const inCart = new Set(lines.map((l) => l.offeringId));
    Promise.all([...inCart].map((oid) =>
      fetch(`/tmf-api/policyManagement/v4/price/teaser?offeringId=${oid}`)
        .then((r) => (r.ok ? r.json() : [])).catch(() => [])))
      .then(async (all) => {
        const seen = new Set();
        const hints = [];
        for (const teaser of all.flat()) {
          const missing = (teaser.relatedOfferingIds || []).filter((rid) => !inCart.has(rid));
          if (!missing.length || seen.has(teaser.name)) continue;
          seen.add(teaser.name);
          const partner = await getOffering(missing[0]).catch(() => null);
          if (partner) hints.push({ message: teaser.message, partner });
        }
        setDealHints(hints);
      })
      .catch(() => setDealHints([]));
  }, [lines]);

  const [priceAdj, setPriceAdj] = useState(null);
  useEffect(() => {
    if (!lines || !lines.length || !Object.keys(prices).length) {
      setPriceAdj(null);
      return;
    }
    let subtotal = 0;
    const ids = new Set();
    const charValues = new Set();
    for (const line of lines) {
      const parts = [
        { id: line.offeringId, characteristics: line.characteristics },
        ...(line.selections || []).map((s) => ({ id: s.offeringId, characteristics: s.characteristics })),
      ];
      for (const part of parts) {
        const offering = offerings[part.id];
        if (!offering) continue;
        ids.add(part.id);
        const m = monthlyTotal(pricesOf(offering, prices, part.characteristics || null));
        if (m) subtotal += m.value * line.quantity;
        for (const [name, value] of Object.entries(part.characteristics || {})) {
          charValues.add(`${name}:${value}`);
        }
      }
    }
    if (subtotal <= 0) {
      setPriceAdj(null);
      return;
    }
    if (isSignedIn()) {
      previewPrice(Number(subtotal.toFixed(2)), [...ids], [...charValues]).then(setPriceAdj);
    } else {
      // guests see what the PUBLIC deals do — identity-conditioned rules
      // (negotiated company terms) never leak to an anonymous basket
      fetch('/tmf-api/policyManagement/v4/price/indicative', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ context: { subtotal: Number(subtotal.toFixed(2)),
          offeringIds: [...ids], characteristicValues: [...charValues] } }),
      }).then((r) => (r.ok ? r.json() : null))
        .then((result) => setPriceAdj(result && (result.adjustments || []).length ? result : null))
        .catch(() => setPriceAdj(null));
    }
  }, [lines, offerings, prices]);

  function pickSlot(next) {
    setSlot(next);
    saveSlotDraft(next);
  }

  if (!lines) {
    return <p className="dim">Loading your cart…</p>;
  }
  if (!lines.length) {
    return <p className="dim">Your cart is empty — <Link to="/">browse the offers</Link>.</p>;
  }

  const hasMobile = lines.some((l) => {
    const names = [l.name, ...(l.selections || []).map((x) => x.name)].join(' ').toLowerCase();
    return names.includes('mobile') || names.includes('5g') || names.includes('subscription');
  });

  const needsShipping = (lines || []).some((l) =>
    physical[l.offeringId] || (l.selections || []).some((s) => physical[s.offeringId]));
  const addressReady = !(needsShipping || needsInstall) || isComplete(address);
  const serviceable = !unqualifiedItem;
  const slotReady = !needsInstall || Boolean(slot);
  const due = dueNow(lines, offerings, prices);
  const signedIn = isSignedIn();
  const cardReady = !due || !signedIn
    || (card.cardNumber.replace(/\s/g, '').length >= 12 && card.expiry.trim() && card.cvc.trim());

  function setField(name, value) {
    const next = { ...address, [name]: value };
    setAddress(next);
    saveDraft(next);
  }

  function lineMonthly(line) {
    const own = offerings[line.offeringId]
      ? monthlyTotal(pricesOf(offerings[line.offeringId], prices, line.characteristics || null)) : null;
    const opts = (line.selections || [])
      .map((s) => offerings[s.offeringId]
        ? monthlyTotal(pricesOf(offerings[s.offeringId], prices, s.characteristics || null)) : null)
      .filter(Boolean);
    if (!own && !opts.length) return null;
    return {
      value: ((own?.value || 0) + opts.reduce((a, m) => a + m.value, 0)) * line.quantity,
      unit: own?.unit || opts[0]?.unit || 'EUR',
    };
  }

  const totals = lines.map(lineMonthly).filter(Boolean);
  const grand = totals.length
    ? { value: totals.reduce((a, m) => a + m.value, 0), unit: totals[0].unit }
    : null;

  async function applyPromo() {
    setPromoError(null);
    try {
      const result = await checkPromotion(promoInput.trim());
      if (!result.valid) {
        setPromo(null);
        setPromoError('That code is not valid.');
        return;
      }
      const applied = { code: promoInput.trim(), ...result };
      setPromo(applied);
      localStorage.setItem('bss.shop.promo', JSON.stringify(applied));
    } catch (e) {
      setPromoError(e.message);
    }
  }

  // The promo's monthly value against the lines it applies to.
  function promoDiscount() {
    if (!promo || !grand) return null;
    const base = lines.reduce((sum, line) => {
      const applies = !promo.appliesTo?.length || promo.appliesTo.includes(line.offeringId);
      const m = lineMonthly(line);
      return applies && m ? sum + m.value : sum;
    }, 0);
    if (base <= 0) return null;
    return { value: -(base * promo.percentage) / 100, unit: grand.unit };
  }

  async function checkout() {
    if (!isSignedIn()) {
      // Cart and typed address survive the redirect in localStorage; this
      // flag makes the checkout resume automatically after sign-in.
      setPendingCheckout();
      await beginLogin();
      return;
    }
    setBusy(true);
    try {
      const order = await performCheckout(lines, due ? card : null, promo?.code || null,
        keepNumber.on ? keepNumber : null);
      localStorage.removeItem('bss.shop.promo');
      if (due && saveCard) {
        // Vault only after the PSP accepted the card; failure is non-fatal.
        await savePaymentMethod(card.cardNumber, card.expiry).catch(() => {});
      }
      await markCartCheckedOut(order.id);
      navigate('/orders');
    } catch (e) {
      setError(e.message);
      setBusy(false);
    }
  }

  return (
    <>
      <h1>Cart</h1>
      {error && <p className="error">{error}</p>}
      <div className="rows">
        {lines.map((line, idx) => {
          const monthly = lineMonthly(line);
          // lines added together as one advertised deal render as a group:
          // a deal header above the first member, members slightly indented
          const dealHeader = line.deal && lines.findIndex((l) => l.deal === line.deal) === idx
            ? <div className="row dealhead" data-testid="deal-group" key={'deal-' + idx}>
                <span>💡 {line.deal}</span>
              </div>
            : null;
          return (
            <React.Fragment key={line.key}>
            {dealHeader}
            <div className={line.deal ? 'row dealline' : 'row'}>
              <div>
                <strong>{line.name}</strong>
                {(line.selections || []).map((s) => (
                  <div className="dim small" key={s.offeringId}>
                    {s.name}
                    {Object.entries(s.characteristics || {}).map(([k, v]) => ` · ${v}`).join('')}
                  </div>
                ))}
                {!(line.selections || []).length && (() => {
                  const spec = specsById[offerings[line.offeringId]?.productSpecification?.id];
                  const configurable = (spec?.productSpecCharacteristic || [])
                    .filter((c) => c.configurable !== false
                      && (c.productSpecCharacteristicValue || []).length);
                  if (!configurable.length) return null;
                  return (
                    <div className="lineconfig" data-testid="line-config">
                      {configurable.map((c) => (
                        <select key={c.name} value={line.characteristics?.[c.name] || ''}
                          onChange={(e) => setLineCharacteristics(line.key, { [c.name]: e.target.value })}>
                          <option value="" disabled>{c.name}…</option>
                          {(c.productSpecCharacteristicValue || []).map((v) => (
                            <option key={v.value} value={v.value}>{v.value}</option>
                          ))}
                        </select>
                      ))}
                    </div>
                  );
                })()}
              </div>
              <div className="rowend">
                {monthly && <span className="linetotal">{monthly.value.toFixed(2)} {monthly.unit}/mo</span>}
                <div className="qty">
                  <button className="ghost" aria-label="decrease"
                          onClick={() => setQuantity(line.key, line.quantity - 1)}>−</button>
                  <span className="qtyval">{line.quantity}</span>
                  <button className="ghost" aria-label="increase"
                          onClick={() => setQuantity(line.key, line.quantity + 1)}>+</button>
                </div>
                <button className="ghost danger" onClick={() => removeLine(line.key)}>Remove</button>
              </div>
            </div>
            </React.Fragment>
          );
        })}
        {grand && (
          <div className="row granded">
            <strong>Total per month</strong>
            <strong className="linetotal">{grand.value.toFixed(2)} {grand.unit}</strong>
          </div>
        )}
        {promo && promoDiscount() && (
          <div className="row promo" data-testid="promo-row">
            <span>Promo <strong>{promo.code}</strong> — {promo.name} (−{promo.percentage}%)</span>
            <span className="linetotal ok">{promoDiscount().value.toFixed(2)} {promoDiscount().unit}/mo</span>
          </div>
        )}
        {priceAdj && priceAdj.adjustments.map((a) => (
          <div className="row promo" data-testid="price-adjustment" key={a.ruleId}>
            <span>{a.label}</span>
            <span className={Number(a.amount) < 0 ? 'linetotal ok' : 'linetotal'}>
              {Number(a.amount) > 0 ? '+' : ''}{Number(a.amount).toFixed(2)} {grand?.unit || 'EUR'}/mo
            </span>
          </div>
        ))}
        {priceAdj && (
          <div className="row granded" data-testid="adjusted-total">
            <strong>{priceAdj.indicative ? t('Indicative price per month') : t('Your price per month')}</strong>
            <strong className="linetotal ok">{Number(priceAdj.total).toFixed(2)} {grand?.unit || 'EUR'}</strong>
          </div>
        )}
        {priceAdj?.indicative && (
          <p className="dim small" data-testid="indicative-note">
            {t('Public deals only — sign in for your exact price; business purchases may price differently.')}
          </p>
        )}
        {dealHints.map((hint) => (
          <div className="row promo" data-testid="deal-hint" key={hint.partner.id}>
            <span>💡 {hint.message}</span>
            <button className="ghost" data-testid="deal-hint-add"
              onClick={() => ensureInCart(hint.partner, hint.message)}>
              {t('Add')} {hint.partner.name} →
            </button>
          </div>
        ))}
        {due && (
          <div className="row granded duenow">
            <strong>Due now</strong>
            <strong className="linetotal">{due.value.toFixed(2)} {due.unit}</strong>
          </div>
        )}
      </div>

      <div className="promobar">
        <input placeholder="Promo code" value={promoInput}
               onChange={(e) => setPromoInput(e.target.value)} />
        <button className="ghost" onClick={applyPromo} disabled={!promoInput.trim()}>Apply</button>
        {promoError && <span className="error small">{promoError}</span>}
      </div>

      {(needsShipping || needsInstall) && (
        <div className="shipping">
          <h2>Shipping address</h2>
          <p className="dim small">
            {needsShipping ? 'Your cart contains devices that will be delivered.'
              : 'The installation address for your services.'}
          </p>
          <div className="addressgrid">
            {ADDRESS_FIELDS.map((f) => (
              <label className="charfield" key={f.name}>
                <span>{f.label}</span>
                <input name={f.name} value={address[f.name] || ''}
                       onChange={(e) => setField(f.name, e.target.value)} />
              </label>
            ))}
          </div>
          {isComplete(address) && needsInstall && (
            <p className={serviceable ? 'serviceability ok' : 'serviceability error'}>
              {serviceable
                ? '✓ Serviceable at your address'
                : unqualifiedItem.eligibilityUnavailabilityReason?.[0]?.label || 'Not serviceable at this address'}
            </p>
          )}
        </div>
      )}

      {needsInstall && serviceable && (
        <div className="install">
          <h2>Installation appointment</h2>
          <p className="dim small">A technician installs your connection — pick a two-hour window.</p>
          {!slots ? <p className="dim">Loading slots…</p> : (
            <div className="options slotgrid">
              {slots.map((s) => {
                const start = s.validFor.startDateTime;
                const label = new Date(start).toLocaleString(undefined,
                  { weekday: 'short', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
                const on = slot?.startDateTime === start;
                return (
                  <label key={start} className={on ? 'option on' : 'option'}>
                    <input type="radio" name="slot" checked={on}
                           onChange={() => pickSlot({ startDateTime: start, endDateTime: s.validFor.endDateTime })} />
                    <span className="optname">{label}</span>
                  </label>
                );
              })}
            </div>
          )}
        </div>
      )}

      {hasMobile && (
        <div className="keepnumber">
          <h2>Your number</h2>
          <label className="keepnum-toggle small">
            <input type="checkbox" checked={keepNumber.on}
                   onChange={(e) => setKeepNumber({ ...keepNumber, on: e.target.checked })} />
            {' '}Keep my current number (port it in)
          </label>
          {keepNumber.on && (
            <div className="addressgrid" style={{ marginTop: '0.5rem' }}>
              <label className="charfield"><span>Your number</span>
                <input name="portNumber" value={keepNumber.number} placeholder="+47 901 12 233"
                       onChange={(e) => setKeepNumber({ ...keepNumber, number: e.target.value })} /></label>
              <label className="charfield"><span>Current provider</span>
                <input name="portProvider" value={keepNumber.currentProvider} placeholder="e.g. OtherTelco"
                       onChange={(e) => setKeepNumber({ ...keepNumber, currentProvider: e.target.value })} /></label>
            </div>
          )}
          {keepNumber.on && <p className="dim small">We'll port it in through your country's number
            registry (NRDB in Norway) and activate your plan on it.</p>}
        </div>
      )}

      {due && (signedIn ? (
        <div className="payment">
          <h2>Payment</h2>
          <p className="dim small">Your card is charged the one-time amount due now.
            Monthly charges arrive on your bill.</p>
          <div className="addressgrid">
            <label className="charfield"><span>Card number</span>
              <input name="cardNumber" value={card.cardNumber} inputMode="numeric"
                     placeholder="4242 4242 4242 4242"
                     onChange={(e) => setCard({ ...card, cardNumber: e.target.value })} /></label>
            <label className="charfield"><span>Expiry</span>
              <input name="expiry" value={card.expiry} placeholder="MM/YY"
                     onChange={(e) => setCard({ ...card, expiry: e.target.value })} /></label>
            <label className="charfield"><span>CVC</span>
              <input name="cvc" value={card.cvc} inputMode="numeric" placeholder="123"
                     onChange={(e) => setCard({ ...card, cvc: e.target.value })} /></label>
          </div>
          <label className="savecard small">
            <input type="checkbox" checked={saveCard}
                   onChange={(e) => setSaveCard(e.target.checked)} />
            {' '}Save this card for future bills
          </label>
        </div>
      ) : (
        <p className="dim small paynote">You'll confirm the payment after signing in.</p>
      ))}

      <div className="cartactions">
        <Link to="/" className="dim">Continue shopping</Link>
        <button className="primary big" onClick={checkout}
                disabled={busy || !addressReady || !serviceable || !slotReady || !cardReady}>
          {busy ? 'Placing order…'
            : !addressReady ? 'Enter shipping address'
            : !serviceable ? 'Not serviceable at this address'
            : !slotReady ? 'Pick an installation slot'
            : !cardReady ? 'Enter card details'
            : due && signedIn ? `Pay ${due.value.toFixed(2)} ${due.unit} & checkout`
            : t('Checkout')}
        </button>
      </div>
    </>
  );
}
