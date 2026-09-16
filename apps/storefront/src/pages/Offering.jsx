import { useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { alsoBought, availabilityFor, beacon, checkConfiguration, getOffering, getSpec, myProducts, priceIndex, queryConfiguration, recommendationOutcome } from '../api.js';
import { CART_EVENT, addToCart, cartLines, ensureInCart } from '../cart.js';
import { fmtAmount, fmtMonthly, fmtPrice, monthlyTotal, pricesOf } from '../money.js';
import { t } from '../i18n.js';

const isChoice = (entry) => Array.isArray(entry.options);

export default function Offering() {
  const { id } = useParams();
  const navigate = useNavigate();
  // arrived from a recommendation? then this page is a DECISION, not a funnel: the why, the before and
  // after, and three honest outcomes — choose it, maybe later, not interested. Back is not a verdict.
  const query = new URLSearchParams(useLocation().search);
  const rec = query.get('rec') ? { decisionId: query.get('rec'), why: query.get('why') || '' } : null;
  const [current, setCurrent] = useState(null); // { name, monthly } — what the customer holds in this category today
  // THE ORACLE (TMF760): for a standalone configurable or per-seat product the server says which values can be
  // picked, whether the picks are orderable, and what they cost. The page renders its answer; it never prices.
  const [space, setSpace] = useState(null);     // queryProductConfiguration: characteristics with isSelectable, fungible
  const [oracleVerdict, setOracleVerdict] = useState(null); // checkProductConfiguration for the current picks + quantity
  const [quantity, setQuantity] = useState(1);
  const [verdict, setVerdict] = useState(null); // deferred | rejected, once told
  const [offering, setOffering] = useState(null);
  const [prices, setPrices] = useState({});
  const [optionOfferings, setOptionOfferings] = useState({}); // option id -> full offering
  const [chosen, setChosen] = useState({});                   // choice name -> [option ids]
  const [specs, setSpecs] = useState({});                     // spec id -> spec
  const [chars, setChars] = useState({});                     // characteristic name -> value
  const [avail, setAvail] = useState({});                     // offering id -> units | null (unmanaged)
  const [extras, setExtras] = useState({});                   // optional component id -> added?
  const [shot, setShot] = useState(0);                        // gallery index
  const [teasers, setTeasers] = useState([]);                 // deals that mention this offering
  const [alsoBoughtItems, setAlsoBoughtItems] = useState([]); // market-basket affinity
  const [inCart, setInCart] = useState(new Set());            // offering ids already in the cart
  const [error, setError] = useState(null);

  // The rail must not suggest what the shopper already has in hand — track the
  // cart's offering ids (lines + their selections), live across cart changes.
  useEffect(() => {
    const refresh = () => cartLines()
      .then((ls) => setInCart(new Set((ls || []).flatMap((l) =>
        [l.offeringId, ...(l.selections || []).map((s) => s.offeringId)]))))
      .catch(() => setInCart(new Set()));
    refresh();
    window.addEventListener(CART_EVENT, refresh);
    return () => window.removeEventListener(CART_EVENT, refresh);
  }, []);

  // TMF620 cardinality: a bundled component with lower limit 0 is optional
  // (an add-on the customer may include); otherwise it is a fixed inclusion.
  const lowerLimit = (e) => e.bundledProductOfferingOption?.numberRelOfferLowerLimit;
  const bundled = offering?.bundledProductOffering || [];
  const choices = bundled.filter(isChoice);
  const optionalComponents = bundled.filter((e) => !isChoice(e) && lowerLimit(e) === 0);
  const fixed = bundled.filter((e) => !isChoice(e) && lowerLimit(e) !== 0);

  useEffect(() => {
    Promise.all([getOffering(id), priceIndex()])
      .then(async ([o, p]) => {
        setOffering(o);
        setPrices(p);
        if (!o.isBundle) queryConfiguration(o.id).then(setSpace).catch(() => setSpace(null));
        if (rec) {
          // what the customer holds today in this category — the "now" side of the decision
          const cat = ((o.category || [])[0] || {}).name || '';
          myProducts().then(async (mine) => {
            for (const prod of (mine || []).filter((x) => x.status === 'active' && x.productOffering?.id && x.productOffering.id !== o.id).slice(0, 8)) {
              try {
                const held = await getOffering(prod.productOffering.id);
                const heldCat = ((held.category || [])[0] || {}).name || '';
                if (!cat || heldCat === cat) { setCurrent({ name: held.name, monthly: monthlyTotal(pricesOf(held, p)) }); return; }
              } catch { /* next */ }
            }
          }).catch(() => {});
        }
        // a consented breadcrumb: this visitor looked at this category
        beacon('view', ((o.category || [])[0] || {}).name || null, o.id);
        // "customers who bought this also bought" — aggregate, fail-soft
        alsoBought(o.id).then(setAlsoBoughtItems).catch(() => {});
        // deals that mention this offering are its shop window — fail-soft
        fetch(`/tmf-api/policyManagement/v4/price/teaser?offeringId=${o.id}`)
          .then((r) => (r.ok ? r.json() : []))
          .then(setTeasers)
          .catch(() => {});
        // the offering's OWN spec carries a standalone device's colours and facts
        if (o.productSpecification?.id) {
          getSpec(o.productSpecification.id)
            .then((sp) => setSpecs((s) => ({ ...s, [sp.id]: sp })))
            .catch(() => {});
        }
        // Resolve every choice option AND optional add-on to its full offering.
        const optionRefs = (o.bundledProductOffering || []).filter(isChoice).flatMap((c) => c.options);
        const optionalRefs = (o.bundledProductOffering || [])
          .filter((e) => !isChoice(e) && e.bundledProductOfferingOption?.numberRelOfferLowerLimit === 0);
        const toResolve = [...optionRefs, ...optionalRefs];
        const full = await Promise.all(toResolve.map((r) => getOffering(r.id)));
        setOptionOfferings(Object.fromEntries(full.map((f) => [f.id, f])));
        const defaults = {};
        for (const c of (o.bundledProductOffering || []).filter(isChoice)) {
          const lower = c.numberRelOfferLowerLimit ?? 1;
          const first = c.default || c.options[0]?.id;
          // "pick up to N" starts empty; anything with a minimum starts with
          // the default so the page is orderable out of the box
          defaults[c.name] = lower === 0 ? [] : [first].filter(Boolean);
        }
        // The radios render before this data arrives — a choice the user
        // already made must never be clobbered by the defaults.
        setChosen((prev) => ({ ...defaults, ...prev }));
        // Shelf check for everything orderable on this page.
        const stockIds = [o.id, ...toResolve.map((r) => r.id)];
        const availability = await Promise.all(stockIds.map(availabilityFor));
        setAvail(Object.fromEntries(stockIds.map((sid, i) => [sid, availability[i]])));
      })
      .catch((e) => setError(e.message));
  }, [id]);

  // The chosen options' specs carry the variant characteristics.
  useEffect(() => {
    const specRefs = Object.values(chosen)
      .map((optionId) => optionOfferings[optionId]?.productSpecification?.id)
      .filter((specId) => specId && !specs[specId]);
    if (!specRefs.length) return;
    Promise.all(specRefs.map(getSpec))
      .then((loaded) => setSpecs((s) => ({
        ...s, ...Object.fromEntries(loaded.map((sp) => [sp.id, sp])),
      })))
      .catch((e) => setError(e.message));
  }, [chosen, optionOfferings]);

  const selectedOptions = useMemo(
    () => Object.values(chosen).flat().map((oid) => optionOfferings[oid]).filter(Boolean),
    [chosen, optionOfferings]);

  // A standalone device configures its own spec (colour, storage) the same
  // way a bundle configures its chosen phone's.
  // ... and so does ANY non-bundle offering whose spec declares a choice (TV screens, home
  // locations, streams): a configurable characteristic with more than one allowed value.
  const ownSpec = offering ? specs[offering.productSpecification?.id] : null;
  const ownConfigurable = Boolean(offering && !offering.isBundle
    && (((offering.category || [])[0] || {}).name === 'Devices'
      || (ownSpec?.productSpecCharacteristic || []).some((c) => c.configurable !== false && (c.productSpecCharacteristicValue || []).length > 1)));

  const activeCharacteristics = useMemo(() => {
    const sources = [...(ownConfigurable ? [offering] : []), ...selectedOptions];
    return sources.flatMap((option) => {
      const spec = specs[option.productSpecification?.id];
      return (spec?.productSpecCharacteristic || [])
        // configurable=false is a FACT (display, battery) — About table, not a picker
        .filter((c) => c.configurable !== false)
        .map((c) => ({ option, characteristic: c }));
    });
  }, [ownConfigurable, offering, selectedOptions, specs]);

  // Descriptive facts across the page's devices — "About this device".
  const deviceFacts = useMemo(() => {
    const sources = [offering, ...selectedOptions].filter(Boolean);
    const rows = [];
    for (const src of sources) {
      const spec = specs[src.productSpecification?.id];
      for (const c of spec?.productSpecCharacteristic || []) {
        if (c.configurable === false) {
          rows.push({ device: spec.name, name: c.name,
            value: c.productSpecCharacteristicValue?.[0]?.value });
        }
      }
    }
    return rows;
  }, [offering, selectedOptions, specs]);

  // Selecting a different phone swaps the characteristic set: keep picks that
  // remain valid, default the rest. Bail out unchanged to avoid re-renders.
  useEffect(() => {
    setChars((prev) => {
      const next = {};
      let changed = false;
      for (const { characteristic } of activeCharacteristics) {
        const values = characteristic.productSpecCharacteristicValue || [];
        const keep = prev[characteristic.name] != null
          && values.some((v) => v.value === prev[characteristic.name]);
        next[characteristic.name] = keep ? prev[characteristic.name] : values[0]?.value;
        if (next[characteristic.name] !== prev[characteristic.name]) changed = true;
      }
      return changed || Object.keys(next).length !== Object.keys(prev).length ? next : prev;
    });
  }, [activeCharacteristics]);

  // every change of a pick or the quantity asks the oracle for the verdict and the price
  useEffect(() => {
    if (!offering || offering.isBundle || !space) return;
    let live = true;
    checkConfiguration(offering.id, chars, quantity).then((v) => { if (live) setOracleVerdict(v); }).catch(() => { if (live) setOracleVerdict(null); });
    return () => { live = false; };
  }, [offering?.id, space, JSON.stringify(chars), quantity]);
  if (error) return <p className="error">{error}</p>;
  if (!offering) return <p className="dim">Loading…</p>;

  // Product imagery: gallery shots come from the catalog's attachment list —
  // internal document store or the operator's own PIM, the page can't tell.
  const gallery = (offering.attachment || [])
    .filter((a) => a.url && !String(a.name || '').startsWith('variant-'));
  const colorPick = Object.entries(chars).find(([k]) => k.toLowerCase() === 'color')?.[1];
  const variantUrl = colorPick
    ? [offering, ...selectedOptions]
      .flatMap((src) => src?.attachment || [])
      .find((a) => a.name === `variant-${colorPick}`)?.url
    : null;
  const heroUrl = variantUrl || gallery[Math.min(shot, Math.max(gallery.length - 1, 0))]?.url;

  const addedExtras = optionalComponents
    .map((e) => (extras[e.id] ? optionOfferings[e.id] : null)).filter(Boolean);
  // the price follows the pick: characteristic-conditioned components
  // (a Titanium Edition premium) join in only when the picks match
  const own = pricesOf(offering, prices, chars);
  const optionPrices = selectedOptions.flatMap((o) => pricesOf(o, prices, chars));
  const extraPrices = addedExtras.flatMap((o) => pricesOf(o, prices));
  const allPrices = [...own, ...optionPrices, ...extraPrices];
  const monthly = monthlyTotal(allPrices);

  const selections = [
    ...selectedOptions.map((option) => ({
      offeringId: option.id,
      name: option.name,
      characteristics: Object.fromEntries(
        activeCharacteristics
          .filter((ac) => ac.option.id === option.id && chars[ac.characteristic.name] != null)
          .map((ac) => [ac.characteristic.name, chars[ac.characteristic.name]])),
    })),
    ...addedExtras.map((o) => ({ offeringId: o.id, name: o.name, characteristics: {} })),
  ];

  const oracle = Boolean(space) && !offering?.isBundle;
  const fungible = Boolean(space?.fungible);
  const oracleRejected = oracle && oracleVerdict && oracleVerdict.state === 'rejected';
  const selectableOf = (name, value) => {
    const ch = (space?.configurationCharacteristic || []).find((c) => c.name === name);
    const v = (ch?.productSpecCharacteristicValue || []).find((x) => String(x.value) === String(value));
    return v ? v.isSelectable !== false : true;
  };

  // A group under its minimum blocks ordering — same rule TMF622 enforces.
  const unmetChoice = choices.find((c) =>
    (chosen[c.name] || []).length < (c.numberRelOfferLowerLimit ?? 1));

  // The page is orderable unless a stock-managed part of it is gone.
  const relevantIds = [offering.id, ...Object.values(chosen).flat()].filter(Boolean);
  const managed = relevantIds.filter((rid) => avail[rid] != null);
  const scarcest = managed.length ? Math.min(...managed.map((rid) => avail[rid])) : null;
  const outOfStock = scarcest === 0;

  async function add() {
    try {
      // a standalone device's own picks ride on the line itself
      const ownChars = ownConfigurable
        ? Object.fromEntries(Object.entries(chars).filter(([, v]) => v != null))
        : null;
      await addToCart(offering, selections, fungible ? quantity : 1, ownChars);
      if (rec) recommendationOutcome(rec.decisionId, 'accepted').catch(() => {});
      navigate('/cart');
    } catch (e) {
      setError(e.message);
    }
  }

  return (
    <div className="detail">
      {offering.isBundle && <span className="tag">Bundle</span>}
      <h1>{offering.name}</h1>
      <p>{offering.description}</p>
      {rec && (() => {
        const term = (offering.productOfferingTerm || [])[0];
        const termWords = term ? `${term.name || t('Commitment')}${term.duration?.amount ? ` · ${term.duration.amount} ${term.duration.units || t('months')}` : ''}` : t('No binding period — cancel any month');
        const newMonthly = monthlyTotal(pricesOf(offering, prices, chars));
        const tell = async (outcome) => {
          try { await recommendationOutcome(rec.decisionId, outcome); } catch { /* the verdict is best-effort */ }
          setVerdict(outcome);
        };
        return (
          <section className="decision" data-testid="offer-decision">
            <div className="assist-label">{t('Why this was recommended')}</div>
            <p data-testid="offer-why">✨ {rec.why || t('Picked from what you have and what you looked at.')}</p>
            <table data-testid="offer-before-after">
              <tbody>
                {current && <tr><td className="dim">{t('Now')}</td><td>{current.name}{current.monthly ? ` · ${fmtMonthly(current.monthly)}` : ''}</td></tr>}
                <tr><td className="dim">{t('With this')}</td><td>{offering.name}{newMonthly ? ` · ${fmtMonthly(newMonthly)}` : ''}</td></tr>
                <tr><td className="dim">{t('Commitment')}</td><td>{termWords}</td></tr>
                <tr><td className="dim">{t('Takes effect')}</td><td>{t('Today. Your number, SIM and discounts carry over; the next bill is split at the change date, so you pay each plan only for its days.')}</td></tr>
              </tbody>
            </table>
            {verdict === 'deferred' && <p className="ok small" data-testid="offer-deferred">{t('Saved for later — we will not show it again for a month.')} <Link to="/">{t('Back to Home')}</Link></p>}
            {verdict === 'rejected' && <p className="ok small" data-testid="offer-rejected">{t('Understood — we will not suggest this again.')} <Link to="/">{t('Back to Home')}</Link></p>}
            {!verdict && (
              <div className="stack">
                <button className="ghost" data-testid="offer-defer" onClick={() => tell('deferred')}>{t('Maybe later')}</button>
                <button className="ghost" data-testid="offer-reject" onClick={() => tell('rejected')}>{t('Not interested')}</button>
                <button className="ghost" data-testid="offer-back" onClick={() => navigate(-1)}>{t('Back')}</button>
              </div>
            )}
          </section>
        );
      })()}

      {teasers.length > 0 && (
        <div className="promos" data-testid="offer-promos">
          {teasers.map((promo, i) => (
            <p key={i} className="promo">💡 {promo.message}
              {promo.audience === 'consumer' && (
                <span className="dim"> — {t('private customers only; company purchases don\'t qualify')}</span>
              )}
              {promo.audience === 'business' && (
                <span className="dim"> — {t('for business customers')}</span>
              )}
              {(promo.relatedOfferingIds || []).slice(0, 1).map((rid) => (
                <span key={rid}>
                  {!unmetChoice && <a className="promolink" href={`#/offering/${rid}`} data-testid="promo-add-deal"
                    onClick={async (e) => {
                      e.preventDefault();
                      try {
                        // one gesture: THIS offering + the partner, straight to
                        // the cart where the deal prices itself
                        const partner = await getOffering(rid);
                        await ensureInCart(offering, promo.message);
                        await ensureInCart(partner, promo.message);
                        navigate('/cart');
                      } catch { navigate(`/offering/${rid}`); }
                    }}>
                    {t('add the deal to cart')} →
                  </a>}
                  <a className="promolink" href={`#/offering/${rid}`}
                    onClick={(e) => { e.preventDefault(); navigate(`/offering/${rid}`); }}>
                    {t('view the partner product')}
                  </a>
                </span>
              ))}
            </p>
          ))}
        </div>
      )}

      {heroUrl && (
        <div className="gallery" data-testid="offer-gallery">
          <img className="hero" data-testid="offer-hero" src={heroUrl} alt={offering.name} />
          {gallery.length > 1 && (
            <div className="thumbs">
              {gallery.map((g, i) => (
                <img key={g.name || i} src={g.url} alt=""
                  className={!variantUrl && i === shot ? 'on' : ''}
                  onClick={() => setShot(i)} />
              ))}
            </div>
          )}
        </div>
      )}

      {fixed.length > 0 && (
        <>
          <h2>What's included</h2>
          <ul className="includes big">
            {fixed.map((c) => <li key={c.id}>{c.name}</li>)}
          </ul>
        </>
      )}

      {choices.map((choice) => {
        const lower = choice.numberRelOfferLowerLimit ?? 1;
        const upper = choice.numberRelOfferUpperLimit ?? 1;
        const picks = chosen[choice.name] || [];
        const multi = upper > 1;
        const hint = multi
          ? (lower === 0 ? `pick up to ${upper}`
            : lower === upper ? `pick ${lower}` : `pick ${lower}–${upper}`)
          : null;
        const toggle = (optId) => setChosen((c) => {
          const cur = c[choice.name] || [];
          if (!multi) return { ...c, [choice.name]: [optId] };
          if (cur.includes(optId)) return { ...c, [choice.name]: cur.filter((x) => x !== optId) };
          if (cur.length >= upper) return c; // full — untick something first
          return { ...c, [choice.name]: [...cur, optId] };
        });
        return (
        <div key={choice.name} className="choice">
          <h2>{choice.name}{hint && <span className="dim" style={{ fontSize: 13, fontWeight: 400 }}> — {hint}</span>}</h2>
          <div className="options">
            {choice.options.map((opt) => {
              const full = optionOfferings[opt.id];
              const optMonthly = full ? monthlyTotal(pricesOf(full, prices)) : null;
              const on = picks.includes(opt.id);
              return (
                <label key={opt.id} className={on ? 'option on' : 'option'}>
                  <input
                    type={multi ? 'checkbox' : 'radio'}
                    name={choice.name}
                    checked={on}
                    disabled={multi && !on && picks.length >= upper}
                    onChange={() => toggle(opt.id)}
                  />
                  {full?.attachment?.[0]?.url && (
                    <img className="optthumb" src={full.attachment[0].url} alt="" />
                  )}
                  <span className="optname">{opt.name}</span>
                  {avail[opt.id] != null && avail[opt.id] <= 5 && (
                    <span className={avail[opt.id] === 0 ? 'stocknote out' : 'stocknote'}>
                      {avail[opt.id] === 0 ? 'out of stock' : `only ${avail[opt.id]} left`}
                    </span>
                  )}
                  {optMonthly && <span className="optprice">+{optMonthly.value.toFixed(2)} {optMonthly.unit}/mo</span>}
                </label>
              );
            })}
          </div>
        </div>
        );
      })}

      {optionalComponents.length > 0 && (
        <div className="choice">
          <h2>Optional add-ons</h2>
          <div className="options">
            {optionalComponents.map((c) => {
              const full = optionOfferings[c.id];
              const optMonthly = full ? monthlyTotal(pricesOf(full, prices)) : null;
              return (
                <label key={c.id} className={extras[c.id] ? 'option on' : 'option'}>
                  <input
                    type="checkbox"
                    data-testid={`extra-${c.id}`}
                    checked={Boolean(extras[c.id])}
                    onChange={() => setExtras((x) => ({ ...x, [c.id]: !x[c.id] }))}
                  />
                  <span className="optname">{c.name}</span>
                  {optMonthly && <span className="optprice">+{optMonthly.value.toFixed(2)} {optMonthly.unit}/mo</span>}
                </label>
              );
            })}
          </div>
        </div>
      )}

      {activeCharacteristics.length > 0 && (
        <div className="chars">
          {activeCharacteristics.map(({ characteristic }) => (
            <label key={characteristic.name} className="charfield">
              <span>{characteristic.name}</span>
              {(characteristic.productSpecCharacteristicValue || []).some((v) => v.value == null && (v.valueFrom != null || v.valueTo != null)) ? (
                (() => { const r = (characteristic.productSpecCharacteristicValue || []).find((v) => v.valueFrom != null || v.valueTo != null); return (
                  <input type="number" data-testid={`range-${characteristic.name}`} min={r.valueFrom ?? undefined} max={r.valueTo ?? undefined} step="1"
                    value={chars[characteristic.name] ?? (r.valueFrom ?? 0)}
                    onChange={(e) => setChars((c) => ({ ...c, [characteristic.name]: e.target.value }))} />
                ); })()
              ) : (
                <select
                  value={chars[characteristic.name] || ''}
                  onChange={(e) => setChars((c) => ({ ...c, [characteristic.name]: e.target.value }))}
                >
                  {(characteristic.productSpecCharacteristicValue || []).map((v) => (
                    <option key={v.value} value={v.value} disabled={!selectableOf(characteristic.name, v.value)}>
                      {v.value}{selectableOf(characteristic.name, v.value) ? '' : ` — ${t('sold out')}`}
                    </option>
                  ))}
                </select>
              )}
            </label>
          ))}
        </div>
      )}

      {fungible && (
        <div className="chars" data-testid="quantity-field">
          <label className="charfield">
            <span>{t('How many')} ({(space?.price || []).find((p) => p.unitOfMeasure)?.unitOfMeasure?.units || t('units')})</span>
            <input type="number" min="1" max="500" step="1" value={quantity} data-testid="quantity"
              onChange={(e) => setQuantity(Math.max(1, parseInt(e.target.value || '1', 10)))} />
          </label>
        </div>
      )}

      {deviceFacts.length > 0 && (
        <>
          <h2>{t('About this device')}</h2>
          <table className="pricetable" data-testid="device-facts">
            <tbody>
              {deviceFacts.map((f, i) => (
                <tr key={`${f.device}-${f.name}-${i}`}>
                  <td>{deviceFacts.some((x) => x.device !== f.device) ? `${f.device} — ${f.name}` : f.name}</td>
                  <td className="num">{f.value}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}

      {oracle && oracleVerdict && oracleVerdict.state === 'accepted' && (
        <>
          <h2>Pricing</h2>
          <table className="pricetable" data-testid="oracle-pricing">
            <tbody>
              {(oracleVerdict.configurationPrice?.priceLine || []).map((l, i) => (
                <tr key={i}>
                  <td>{l.name}{l.how ? <span className="dim small"> · {l.how}</span> : null}</td>
                  <td className="num">{fmtAmount(Number(l.amount), l.price?.unit)}{l.priceType === 'recurring' ? '/month' : l.priceType === 'oneTime' ? ` ${t('once')}` : ''}</td>
                </tr>
              ))}
              <tr className="total">
                <td>Total per month</td>
                <td className="num" data-testid="oracle-monthly">{fmtAmount(Number(oracleVerdict.configurationPrice?.monthlyTotal?.value || 0), oracleVerdict.configurationPrice?.monthlyTotal?.unit)}</td>
              </tr>
              {Number(oracleVerdict.configurationPrice?.oneTimeTotal?.value || 0) > 0 && (
                <tr><td>{t('Once')}</td><td className="num">{fmtAmount(Number(oracleVerdict.configurationPrice.oneTimeTotal.value), oracleVerdict.configurationPrice.oneTimeTotal.unit)}</td></tr>
              )}
            </tbody>
          </table>
          {(oracleVerdict.productConfiguration?.configurationAction || []).map((a, i) => (
            <p key={i} className="dim small" data-testid="oracle-action">{a.description}{a.isSelected ? ` — ${t('added for you')}` : ''}</p>
          ))}
        </>
      )}
      {oracleRejected && (
        <p className="error" data-testid="oracle-rejected">{(oracleVerdict.message || []).join(' · ')}</p>
      )}
      {!oracle && allPrices.length > 0 && (
        <>
          <h2>Pricing</h2>
          <table className="pricetable">
            <tbody>
              {allPrices.map((p) => (
                <tr key={p.id}>
                  <td>{p.name}</td>
                  <td className="num">{fmtPrice(p)}</td>
                </tr>
              ))}
              {monthly && (
                <tr className="total">
                  <td>Total per month</td>
                  <td className="num">{fmtAmount(monthly.value, monthly.unit)}</td>
                </tr>
              )}
            </tbody>
          </table>
          {(window.BSS_STOREFRONT_CONFIG || {}).priceNote && (
            <p className="dim small" data-testid="price-note">{(window.BSS_STOREFRONT_CONFIG || {}).priceNote}</p>
          )}
        </>
      )}

      {scarcest != null && (
        <p className={outOfStock ? 'stockline error' : 'stockline dim'}>
          {outOfStock ? 'Out of stock' : scarcest <= 5 ? `Only ${scarcest} left in stock` : 'In stock'}
        </p>
      )}
      {unmetChoice && (
        <p className="dim" data-testid="choice-hint">
          {unmetChoice.name}: pick at least {unmetChoice.numberRelOfferLowerLimit ?? 1} to continue.
        </p>
      )}
      <button className="primary big" onClick={add} disabled={outOfStock || Boolean(unmetChoice) || oracleRejected}>
        {outOfStock ? t('Out of stock') : t('Add to cart')}
      </button>

      {(() => {
        // Filter the rail against what's already chosen: this offering, the
        // configurator's current picks, added add-ons, and the cart's contents —
        // plus EVERY option of this bundle's choice groups (structural: the
        // bundle already has a slot for a phone, so recommending other phones
        // is noise even when they're not the current pick).
        const already = new Set([
          id,
          ...Object.values(chosen).flat(),
          ...Object.entries(extras).filter(([, on]) => on).map(([eid]) => eid),
          ...choices.flatMap((c) => (c.options || []).map((o) => o.id)),
          ...inCart,
        ]);
        const rail = alsoBoughtItems.filter((it) => !already.has(it.offering.id));
        return rail.length > 0 && (
          <section data-testid="also-bought" style={{ marginTop: 28 }}>
            <h2>{t('Customers who bought this also bought')}</h2>
            <div className="cards">
              {rail.map((it) => (
                <Link key={it.offering.id} to={`/offering/${it.offering.id}`}
                      className="card" data-testid="also-bought-item"
                      style={{ textDecoration: 'none' }}>
                  <b>{it.offering.name}</b>
                </Link>
              ))}
            </div>
          </section>
        );
      })()}
    </div>
  );
}
