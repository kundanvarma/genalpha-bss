import React, { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { availabilityFor, checkQualification, deliveryOptions, getOffering, getSpec, myParty, previewPrice, priceIndex, queryServiceQualification, searchTimeSlots, updateMyParty, verifyDeliveryAddress } from '../api.js';
import { beginLogin, isCustomer, isSignedIn, switchAccount } from '../auth.js';
import { CART_EVENT, cartLines, ensureInCart, markCartCheckedOut, removeLine, setLineCharacteristics, setQuantity } from '../cart.js';
import { ADDRESS_FIELDS, addressOf, isComplete, loadDraft, registeredAddressOf, saveDraft, withRegisteredAddress } from '../address.js';
import { dueNow, loadDevicePlanDraft, loadSlotDraft, performCheckout, qualificationItems,
  saveDevicePlanDraft, saveSlotDraft } from '../checkout.js';
import { checkPromotion, confirmPayment, createPaymentSession, financingQuote, numberOffers,
  paymentMethods, quoteTradeIn, savePaymentMethod } from '../api.js';
import { monthlyTotal, oneTimeTotal, pricesOf } from '../money.js';
import { setPendingCheckout } from '../pending.js';
import { t } from '../i18n.js';

// How a payment method reads to the shopper (the API gives the machine name).
const PAY_LABEL = { card: 'Card', klarna: 'Klarna', paypal: 'PayPal' };
const payLabel = (m) => PAY_LABEL[m] || (m ? m.charAt(0).toUpperCase() + m.slice(1) : 'Card');

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
  const [footprint, setFootprint] = useState(null); // TMF645: what the network delivers here
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
  // Credit-frozen: order create answered 422 CREDIT_FROZEN — a freeze at the
  // credit bureau, NOT a decline. Distinct panel, prepaid path offered.
  const [creditFrozen, setCreditFrozen] = useState(
    () => localStorage.getItem('bss.shop.creditFrozen') === '1');
  useEffect(() => { localStorage.removeItem('bss.shop.creditFrozen'); }, []);
  // The device plan: financing choice + trade-in, drafted here, executed by
  // performCheckout (survives the login redirect and the BNPL hop).
  const [devicePlan, setDevicePlan] = useState(loadDevicePlanDraft());
  const updateDevicePlan = (next) => { setDevicePlan(next); saveDevicePlanDraft(next); };
  const [keepNumber, setKeepNumber] = useState({ on: false, number: '', currentProvider: '', portDate: '' });
  // Choose-your-number: a shortlist from the pool; '' = auto-assign (unchanged).
  const [numberWish, setNumberWish] = useState('');
  const [numberChoices, setNumberChoices] = useState([]);
  const [numberShuffle, setNumberShuffle] = useState(0);
  useEffect(() => {
    numberOffers(numberShuffle || null).then((offers) => {
      setNumberChoices((offers || []).map((o) => o.msisdn));
      setNumberWish((w) => (w && !(offers || []).some((o) => o.msisdn === w) ? '' : w));
    }).catch(() => setNumberChoices([]));
  }, [numberShuffle]);
  // C3 — how the customer wants their SIM: eSIM (instant, no parcel) or a
  // physical SIM card shipped by the carrier. Default eSIM.
  const [simType, setSimType] = useState('esim');
  // C-P3: which carrier + method delivers the parcel — the operator's menu, the
  // shopper's pick. Selection key is `${carrier}:${method}`; default is the first
  // home option once the menu loads.
  const [deliverySel, setDeliverySel] = useState(null);
  const [deliveryOpts, setDeliveryOpts] = useState(null);
  const [pickupId, setPickupId] = useState('');
  useEffect(() => {
    if (!address.postCode) { setDeliveryOpts(null); return; }
    deliveryOptions(address.postCode).then(setDeliveryOpts).catch(() => setDeliveryOpts(null));
  }, [address.postCode]);
  // Default the delivery pick to the first home option once the menu arrives.
  useEffect(() => {
    if (deliveryOpts && deliveryOpts.length && !deliverySel) {
      const first = deliveryOpts.find((o) => o.method === 'home') || deliveryOpts[0];
      setDeliverySel(`${first.carrier || 'default'}:${first.method}`);
    }
  }, [deliveryOpts, deliverySel]);
  // PSP-P2: how to pay — card, or a redirect method (Klarna) the operator offers.
  const [payMethod, setPayMethod] = useState('card');
  const [payMethods, setPayMethods] = useState([{ method: 'card', redirect: false }]);
  useEffect(() => { paymentMethods().then(setPayMethods).catch(() => {}); }, []);
  // Redirect return leg (Klarna/PayPal): the provider's approve page sends us back;
  // the session was stashed before the hop, so confirm it against the provider that
  // actually served (failover-safe) and finish the order.
  useEffect(() => {
    let stash = null;
    try { stash = JSON.parse(localStorage.getItem('bss.shop.redirectpay') || 'null'); } catch { stash = null; }
    const returned = /[?&](klarna_session|paypal_order|resume)=/.test(window.location.search);
    if (!stash || !stash.sessionId || !returned || !lines || !lines.length) return;
    (async () => {
      setBusy(true);
      try {
        const payment = await confirmPayment(stash.provider, stash.sessionId);
        const order = await performCheckout(lines, null, stash.promoCode || null,
          stash.keepNumber && stash.keepNumber.on ? stash.keepNumber : null,
          stash.simType || 'esim', stash.delivery || null, payment, stash.numberWish || null);
        localStorage.removeItem('bss.shop.redirectpay');
        window.history.replaceState(null, '', '/shop/cart');
        await markCartCheckedOut(order.id);
        navigate('/orders');
      } catch (e) {
        setError(e.message);
        setBusy(false);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lines]);

  useEffect(() => {
    const refresh = () => { cartLines().then(setLines).catch((e) => setError(e.message)); };
    refresh();
    window.addEventListener(CART_EVENT, refresh);
    return () => window.removeEventListener(CART_EVENT, refresh);
  }, []);

  // A stale device plan (its line left the cart) must not haunt the checkout.
  useEffect(() => {
    if (!lines || !devicePlan) return;
    if (!lines.some((l) => l.offeringId === devicePlan.offeringId)) updateDevicePlan(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lines]);

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
  const [party, setParty] = useState(null);
  useEffect(() => {
    if (!isSignedIn()) return;
    myParty().then((p) => {
      setParty(p);
      if (isComplete(loadDraft())) return;
      const saved = addressOf(p);
      if (saved) {
        setAddress(saved);
        saveDraft(saved);
      }
    }).catch(() => {});
  }, []);

  // Registry verification (freg F-P2): is the signed-in shopper registered at
  // the typed address? Debounced so the form doesn't flood the lookup ledger;
  // guests are never asked (no identity to match). The answer tags the
  // delivery with addressSource registry|manual for the risk seam.
  const partyName = party ? [party.givenName, party.familyName].filter(Boolean).join(' ').trim() : '';
  const [regMatch, setRegMatch] = useState(null);
  useEffect(() => {
    if (!partyName || !isComplete(address)) { setRegMatch(null); return; }
    const t = setTimeout(() => {
      verifyDeliveryAddress(address, partyName).then(setRegMatch).catch(() => setRegMatch(null));
    }, 600);
    return () => clearTimeout(t);
  }, [partyName, address.street1, address.postCode, address.city, address.country]);
  const registryVerified = regMatch?.outcome === 'match';

  // A fresh match stamps the party with the registered address (a SECOND
  // postalAddress medium, source=folkeregisteret — the typed one stays).
  useEffect(() => {
    if (!party || !registryVerified || !regMatch.registeredAddress) return;
    const current = registeredAddressOf(party);
    const reg = regMatch.registeredAddress;
    if (current && current.street1 === reg.street1 && current.postCode === reg.postCode) return;
    updateMyParty({ contactMedium: withRegisteredAddress(party,
      { ...reg, source: 'folkeregisteret', verifiedAt: new Date().toISOString() }) })
      .then(setParty).catch(() => {});
  }, [regMatch, party, registryVerified]);

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

  // TMF645: the technical footprint at this address — technology and speed
  useEffect(() => {
    const postCode = address.postCode;
    if (!postCode) { setFootprint(null); return; }
    queryServiceQualification({ postCode, city: address.city, country: address.country })
      .then((result) => setFootprint({ result, postCode }))
      .catch(() => setFootprint(null));
  }, [address.postCode]);

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
    // the catalog's word first: a line whose offering sits in 'Mobile plans'
    // IS a mobile line, whatever the brand calls it (name keywords are the
    // fallback for offerings that predate category tagging)
    const inMobileCategory = [l, ...(l.selections || [])].some((x) =>
      ((offerings[x.offeringId]?.category || [])[0] || {}).name === 'Mobile plans');
    const names = [l.name, ...(l.selections || []).map((x) => x.name)].join(' ').toLowerCase();
    return inMobileCategory || names.includes('mobile') || names.includes('5g') || names.includes('subscription');
  });

  const needsShipping = (lines || []).some((l) =>
    physical[l.offeringId] || (l.selections || []).some((s) => physical[s.offeringId]))
    || (hasMobile && simType === 'physical'); // a physical SIM ships too
  // WHAT ships — so "eSIM (nothing to ship)" next to a delivery block never
  // reads as a contradiction: the parcel is the phone, and we say so.
  const shipNames = [
    ...new Set((lines || []).flatMap((l) => [
      ...(physical[l.offeringId] ? [l.name] : []),
      ...(l.selections || []).filter((s) => physical[s.offeringId]).map((s) => s.name),
    ])),
    ...(hasMobile && simType === 'physical' ? ['your SIM card'] : []),
  ];
  // The operator's delivery menu, split for the picker: a home option per carrier,
  // plus any carrier's pickup points. optKey pairs a carrier with a method.
  const optKey = (o) => `${o.carrier || 'default'}:${o.method}`;
  const isPickupMethod = (m) => m === 'pickupPoint' || m === 'locker';
  const menuOpts = (deliveryOpts || []).filter((o) => o.method === 'home'
    || (isPickupMethod(o.method) && (o.points || []).length));
  const selectedOpt = menuOpts.find((o) => optKey(o) === deliverySel)
    || menuOpts.find((o) => o.method === 'home') || menuOpts[0] || null;
  const showDeliveryPicker = needsShipping && menuOpts.length > 1;
  const deliveryReady = !showDeliveryPicker || !selectedOpt
    || !isPickupMethod(selectedOpt.method) || Boolean(pickupId);
  const addressReady = !(needsShipping || needsInstall) || isComplete(address);
  // The address home delivery would ship to, said wherever the shopper picks —
  // a prefilled form higher up must never leave the destination unstated.
  const shipAddress = isComplete(address)
    ? `${address.street1}, ${address.postCode} ${address.city}` : null;
  const scrollToAddress = () => {
    const el = document.querySelector('.shipping');
    el?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    // Focus + select the street field so the click visibly answers even when
    // the form is already on-screen (a scroll alone reads as a dead button).
    const input = el?.querySelector('input[name="street1"]');
    if (input) setTimeout(() => { input.focus(); input.select(); }, 350);
  };
  const serviceable = !unqualifiedItem;
  const slotReady = !needsInstall || Boolean(slot);
  const due = dueNow(lines, offerings, prices);
  // Device commerce: the FIRST physical Devices-category line carries the
  // trade-in widget and the financing chooser. Operator-book instalments move
  // the principal off today's charge (it lands monthly on the bill); the
  // trade-in estimate is a credit paid out after grading — shown, not charged.
  const categoryNameOf = (o) => ((o?.category || [])[0] || {}).name || '';
  const deviceLine = lines.find((l) => physical[l.offeringId]
    && categoryNameOf(offerings[l.offeringId]) === 'Devices') || null;
  const deviceOffering = deviceLine ? offerings[deviceLine.offeringId] : null;
  const devicePrice = deviceOffering
    ? oneTimeTotal(pricesOf(deviceOffering, prices, deviceLine.characteristics || null)) : null;
  const planActive = devicePlan && deviceLine && devicePlan.offeringId === deviceLine.offeringId
    ? devicePlan : null;
  const financedOff = planActive && planActive.financing === 'OPERATOR_BOOK' && planActive.principal
    ? planActive.principal.value * deviceLine.quantity : 0;
  const chargeDue = due && due.value - financedOff > 0.005
    ? { value: due.value - financedOff, unit: due.unit } : null;
  const tradeCredit = planActive?.tradeIn
    ? { value: Number(planActive.tradeIn.estimatedValue), unit: planActive.tradeIn.currency || due?.unit || 'EUR' }
    : null;
  const signedIn = isSignedIn();
  // Any redirect method the operator offers (Klarna, PayPal, …), plus card.
  const redirectMethods = (payMethods || []).filter((m) => m.redirect);
  const selectedPay = (payMethods || []).find((m) => m.method === payMethod) || { method: 'card', redirect: false };
  const payingRedirect = Boolean(selectedPay.redirect);
  const cardReady = !chargeDue || !signedIn || payingRedirect
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
    if (!isCustomer()) {
      // A staff session carried in by SSO is not a shopper — an order must never
      // be placed under a non-customer identity. Offer the switch instead.
      setError('You\'re signed in as a staff account. Switch to a customer account to check out.');
      switchAccount();
      return;
    }
    setBusy(true);
    try {
      const sel = selectedOpt;
      const chosenPoint = sel && isPickupMethod(sel.method)
        ? (sel.points || []).find((p) => p.id === pickupId) : null;
      const delivery = chosenPoint
        ? { method: 'pickupPoint', carrier: sel.carrier,
          pickupPointId: chosenPoint.id, pickupPointName: chosenPoint.name }
        : { method: 'home', carrier: sel?.carrier || null,
          // the risk seam (F-P3) reads this off the place: registry-verified or not
          addressSource: registryVerified ? 'registry' : 'manual' };
      if (payingRedirect && chargeDue) {
        // Redirect to the provider (Klarna/PayPal) to approve. Open the session
        // first, then stash the SERVED provider + session id (failover may switch
        // the provider) so the return leg confirms the right one; the choices ride
        // the hop and the cart survives server-side.
        const session = await createPaymentSession({ method: payMethod,
          amount: { value: chargeDue.value, unit: chargeDue.unit }, returnUrl: `${window.location.origin}/shop/cart` });
        localStorage.setItem('bss.shop.redirectpay', JSON.stringify({
          provider: session.provider, sessionId: session.sessionId,
          simType: hasMobile ? simType : 'esim', delivery, numberWish: numberWish || null,
          keepNumber: keepNumber.on ? keepNumber : null, promoCode: promo?.code || null }));
        window.location.href = session.redirectUrl;
        return;
      }
      const order = await performCheckout(lines, chargeDue ? card : null, promo?.code || null,
        keepNumber.on ? keepNumber : null, hasMobile ? simType : 'esim', delivery,
        null, numberWish || null);
      localStorage.removeItem('bss.shop.promo');
      if (chargeDue && saveCard) {
        // Vault only after the PSP accepted the card; failure is non-fatal.
        await savePaymentMethod(card.cardNumber, card.expiry).catch(() => {});
      }
      await markCartCheckedOut(order.id);
      navigate('/orders');
    } catch (e) {
      if (e.code === 'CREDIT_FROZEN') {
        // A freeze is not a decline — say so, and offer the prepaid road.
        setCreditFrozen(true);
        setBusy(false);
        return;
      }
      setError(e.message);
      setBusy(false);
    }
  }

  return (
    <>
      <h1>{t('Cart')}</h1>
      {creditFrozen && (
        <div className="freezepanel" data-testid="credit-frozen">
          <strong>❄️ Your credit information is frozen</strong>
          <p className="small">
            Your order couldn't be placed because your credit record is frozen at the credit
            bureau — a protection you (or your bank) switched on, not a payment decline.
            You can <Link to="/">choose a prepaid start instead</Link> — no credit check, pay as
            you go — or lift the freeze with the credit bureau and try again.
          </p>
          <button className="ghost" onClick={() => setCreditFrozen(false)}>Try again</button>
        </div>
      )}
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
                <button className="ghost danger" onClick={() => removeLine(line.key)}>{t('Remove')}</button>
              </div>
            </div>
            </React.Fragment>
          );
        })}
        {grand && (
          <div className="row granded">
            <strong>{t('Total per month')}</strong>
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
        {planActive && planActive.financing !== 'FULL' && (
          <div className="row promo" data-testid="financing-row">
            <span>📱 {deviceLine.name} — {(planActive.monthlyAmount * deviceLine.quantity).toFixed(2)} {planActive.principal.unit}/mo
              × {planActive.termMonths} months (total {(planActive.totalCostOfOwnership * deviceLine.quantity).toFixed(2)} {planActive.principal.unit})</span>
            {planActive.financing === 'OPERATOR_BOOK' ? (
              <span className="linetotal ok">−{financedOff.toFixed(2)} {planActive.principal.unit} today</span>
            ) : (
              <span className="linetotal">via the pay-later provider</span>
            )}
          </div>
        )}
        {chargeDue && (
          <div className="row granded duenow">
            <strong>{t('Due now')}</strong>
            <strong className="linetotal">{chargeDue.value.toFixed(2)} {chargeDue.unit}</strong>
          </div>
        )}
        {tradeCredit && tradeCredit.value > 0 && (
          <div className="row promo" data-testid="trade-in-credit">
            <span>♻️ Trade-in credit for your old phone — paid out once we receive and check it</span>
            <span className="linetotal ok">−{tradeCredit.value.toFixed(2)} {tradeCredit.unit}</span>
          </div>
        )}
        {tradeCredit && tradeCredit.value > 0 && chargeDue && (
          <div className="row granded" data-testid="due-after-tradein">
            <strong>Total after trade-in</strong>
            <strong className="linetotal ok">
              {Math.max(chargeDue.value - tradeCredit.value, 0).toFixed(2)} {chargeDue.unit}
            </strong>
          </div>
        )}
      </div>

      <div className="promobar">
        <input placeholder={t('Promo code')} value={promoInput}
               onChange={(e) => setPromoInput(e.target.value)} />
        <button className="ghost" onClick={applyPromo} disabled={!promoInput.trim()}>{t('Apply')}</button>
        {promoError && <span className="error small">{promoError}</span>}
      </div>

      {deviceLine && devicePrice && (
        <div className="devicecommerce" data-testid="device-commerce">
          <h2>Your new {deviceLine.name}</h2>
          <FinancingChooser deviceLine={deviceLine} devicePrice={devicePrice}
            plan={planActive} signedIn={signedIn}
            onPlan={(p) => {
              updateDevicePlan(p);
              // pay-later approves with the redirect provider — pre-select it
              if (p?.financing === 'BNPL' && redirectMethods.length) {
                setPayMethod(redirectMethods[0].method);
              }
            }} />
          <TradeInPanel deviceLine={deviceLine} devicePrice={devicePrice}
            plan={planActive} signedIn={signedIn}
            onTradeIn={(tradeIn) => updateDevicePlan({
              ...(planActive || freshDevicePlan(deviceLine, devicePrice)), tradeIn })} />
        </div>
      )}

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
          {isComplete(address) && footprint?.postCode === address.postCode
            && (footprint.result.serviceQualificationItem || []).length > 0 && (() => {
              // TMF645: name the best technology this address can actually have
              const label = { fiber: 'Fiber', vdsl: 'VDSL', '5g-fwa': '5G broadband' };
              const best = footprint.result.serviceQualificationItem
                .map((i) => Object.fromEntries((i.service.serviceCharacteristic || [])
                  .map((c) => [c.name, c.value])))
                .sort((a, b) => (b.maxDownstreamMbps || 0) - (a.maxDownstreamMbps || 0))[0];
              return (
                <p className="serviceability footprint">
                  {(label[best.technology] || best.technology)}
                  {' up to '}{best.maxDownstreamMbps}{' Mbit/s at your address'}
                </p>
              );
            })()}
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
        <div className="keepnumber simchoice">
          <h2>{t('Your SIM')}</h2>
          <div className="simopts">
            <button type="button" className={`simopt ${simType === 'esim' ? 'on' : ''}`}
                    onClick={() => setSimType('esim')}>
              <span className="simopt-t">⚡ eSIM</span>
              <span className="simopt-d">{t('Activates instantly — nothing to ship')}</span>
            </button>
            <button type="button" className={`simopt ${simType === 'physical' ? 'on' : ''}`}
                    onClick={() => setSimType('physical')}>
              <span className="simopt-t">📦 {t('Physical SIM')}</span>
              <span className="simopt-d">{t('Ships to your door — track it every step')}</span>
            </button>
          </div>
        </div>
      )}

      {showDeliveryPicker && (
        <div className="delivery-method">
          <h2>{t('Delivery')}</h2>
          {shipNames.length > 0 && (
            <p className="dim small" data-testid="ships-to-you">📦 Ships to you: {shipNames.join(', ')}</p>
          )}
          <div className="simopts deliveryopts">
            {menuOpts.map((o) => {
              const key = optKey(o);
              const pickup = isPickupMethod(o.method);
              return (
                <button type="button" key={key} data-testid="delivery-opt"
                        className={`simopt ${deliverySel === key ? 'on' : ''}`}
                        onClick={() => { setDeliverySel(key); if (!pickup) setPickupId(''); }}>
                  <span className="simopt-t">{pickup ? `📍 ${t('Pickup point')}` : `🏠 ${t('Home delivery')}`} · {o.carrierName}</span>
                  <span className="simopt-d">{pickup
                    ? `Collect at a ${o.carrierName} point near you`
                    : (shipAddress
                      ? `Delivered to ${shipAddress} by ${o.carrierName}`
                      : `Delivered to your door by ${o.carrierName}`)}</span>
                </button>
              );
            })}
          </div>
          {selectedOpt && isPickupMethod(selectedOpt.method) && (
            <select className="pickup-select" value={pickupId} onChange={(e) => setPickupId(e.target.value)}>
              <option value="">Choose a {selectedOpt.carrierName} pickup point…</option>
              {(selectedOpt.points || []).map((p) => (
                <option key={p.id} value={p.id}>{p.name} — {p.address}</option>
              ))}
            </select>
          )}
          {selectedOpt && !isPickupMethod(selectedOpt.method) && shipAddress && (
            <p className="dim small" data-testid="delivering-to">
              🏠 Delivering to: {shipAddress}
              {registryVerified && <span className="regbadge" data-testid="registry-verified"> ✓ registered address</span>}
              {' · '}
              <button type="button" className="linkbtn" onClick={scrollToAddress}>Change</button>
            </p>
          )}
          {selectedOpt && !isPickupMethod(selectedOpt.method) && regMatch
            && (regMatch.outcome === 'mismatch' || regMatch.outcome === 'no_data') && (
            <p className="dim small" data-testid="registry-unverified">
              Could not verify this address against the national register.
              {regMatch.registeredAddress && (
                <>
                  {' '}
                  <button type="button" className="linkbtn" data-testid="use-registered"
                          onClick={() => { const r = regMatch.registeredAddress;
                            const next = { street1: r.street1, postCode: r.postCode, city: r.city, country: r.country };
                            setAddress(next); saveDraft(next); }}>
                    Use my registered address
                  </button>
                </>
              )}
            </p>
          )}
        </div>
      )}
      {needsShipping && !showDeliveryPicker && selectedOpt && selectedOpt.carrierName && (
        // one carrier only — no picker, but still SAY who delivers, of what, and to where
        <p className="dim small" data-testid="delivery-by">
          🚚 Home delivery by {selectedOpt.carrierName}
          {shipAddress ? ` to ${shipAddress}` : ''}
          {registryVerified && <span className="regbadge"> ✓ registered address</span>}
          {shipNames.length > 0 ? ` — ${shipNames.join(', ')}` : ' — track it to your door'}
          {shipAddress ? <>{' · '}<button type="button" className="linkbtn" onClick={scrollToAddress}>Change</button></> : null}
        </p>
      )}

      {hasMobile && (
        <div className="keepnumber">
          <h2>{t('Your number')}</h2>
          <label className="keepnum-toggle small">
            <input type="checkbox" checked={keepNumber.on}
                   onChange={(e) => setKeepNumber({ ...keepNumber, on: e.target.checked })} />
            {' '}{t('Keep my current number (port it in)')}
          </label>
          {keepNumber.on && (
            <div className="addressgrid" style={{ marginTop: '0.5rem' }}>
              <label className="charfield"><span>{t('Your number')}</span>
                <input name="portNumber" value={keepNumber.number} placeholder="+47 901 12 233"
                       onChange={(e) => setKeepNumber({ ...keepNumber, number: e.target.value })} /></label>
              <label className="charfield"><span>Current provider</span>
                <input name="portProvider" value={keepNumber.currentProvider} placeholder="e.g. OtherTelco"
                       onChange={(e) => setKeepNumber({ ...keepNumber, currentProvider: e.target.value })} /></label>
              <label className="charfield"><span>Port-in date (optional)</span>
                <input name="portDate" type="date" value={keepNumber.portDate}
                       min={new Date(Date.now() + 86400000).toISOString().slice(0, 10)}
                       onChange={(e) => setKeepNumber({ ...keepNumber, portDate: e.target.value })} /></label>
            </div>
          )}
          {keepNumber.on && <p className="dim small">We'll port it in through your country's number
            registry (NRDB in Norway) and activate your plan on it.
            {keepNumber.portDate
              ? ` Your number moves on ${keepNumber.portDate} — your old plan keeps working until then.`
              : ' No date picked = as soon as possible.'}</p>}
          {!keepNumber.on && numberChoices.length > 0 && (
            <div className="number-choice" data-testid="number-choice">
              <p className="dim small" style={{ margin: '0.6rem 0 0.3rem' }}>
                {t('…or pick your new number (optional — none picked = we assign one):')}</p>
              <div className="simopts" style={{ gap: 8 }}>
                {numberChoices.map((n) => (
                  <button type="button" key={n} data-testid="number-option"
                          className={`simopt ${numberWish === n ? 'on' : ''}`}
                          style={{ flex: '0 1 auto', padding: '6px 12px' }}
                          onClick={() => setNumberWish(numberWish === n ? '' : n)}>
                    <span className="simopt-t" style={{ fontVariantNumeric: 'tabular-nums' }}>{n}</span>
                  </button>
                ))}
                <button type="button" className="ghost" data-testid="number-shuffle"
                        onClick={() => setNumberShuffle((x) => x + 1)}>{t('Show me others')}</button>
              </div>
            </div>
          )}
        </div>
      )}

      {chargeDue && (signedIn ? (
        <div className="payment">
          <h2>{t('Payment')}</h2>
          {redirectMethods.length > 0 && (
            <div className="simopts" style={{ marginBottom: '0.8rem' }}>
              <button type="button" className={`simopt ${payMethod === 'card' ? 'on' : ''}`}
                      onClick={() => setPayMethod('card')}>
                <span className="simopt-t">💳 {t('Card')}</span>
                <span className="simopt-d">{t('Pay the one-time amount now')}</span>
              </button>
              {redirectMethods.map((m) => (
                <button type="button" key={m.method} className={`simopt ${payMethod === m.method ? 'on' : ''}`}
                        onClick={() => setPayMethod(m.method)}>
                  <span className="simopt-t">{payLabel(m.method)}</span>
                  <span className="simopt-d">{t('Approve at')} {payLabel(m.method)}</span>
                </button>
              ))}
            </div>
          )}
          {payingRedirect ? (
            <p className="dim small">You'll approve the payment at {payLabel(payMethod)}, then come back to finish your order.</p>
          ) : (
            <>
              <p className="dim small">{t('Your card is charged the one-time amount due now. Monthly charges arrive on your bill.')}</p>
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
            </>
          )}
        </div>
      ) : (
        <p className="dim small paynote">You'll confirm the payment after signing in.</p>
      ))}

      <div className="cartactions">
        <Link to="/" className="dim">{t('Continue shopping')}</Link>
        <button className="primary big" onClick={checkout}
                disabled={busy || !addressReady || !serviceable || !slotReady || !deliveryReady || !cardReady}>
          {busy ? 'Placing order…'
            : !addressReady ? 'Enter shipping address'
            : !serviceable ? 'Not serviceable at this address'
            : !slotReady ? 'Pick an installation slot'
            : !deliveryReady ? 'Choose a pickup point'
            : !cardReady ? 'Enter card details'
            : payingRedirect && chargeDue && signedIn ? `Continue to ${payLabel(payMethod)} · ${chargeDue.value.toFixed(2)} ${chargeDue.unit}`
            : chargeDue && signedIn ? `Pay ${chargeDue.value.toFixed(2)} ${chargeDue.unit} & checkout`
            : t('Checkout')}
        </button>
      </div>
    </>
  );
}

/** The device plan a fresh trade-in or financing pick starts from. */
function freshDevicePlan(deviceLine, devicePrice) {
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
function FinancingChooser({ deviceLine, devicePrice, plan, signedIn, onPlan }) {
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
function TradeInPanel({ deviceLine, devicePrice, plan, signedIn, onTradeIn }) {
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
