import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { availabilityFor, getOffering, getSpec, priceIndex } from '../api.js';
import { addToCart } from '../cart.js';
import { fmtPrice, monthlyTotal, pricesOf } from '../money.js';
import { t } from '../i18n.js';

const isChoice = (entry) => Array.isArray(entry.options);

export default function Offering() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [offering, setOffering] = useState(null);
  const [prices, setPrices] = useState({});
  const [optionOfferings, setOptionOfferings] = useState({}); // option id -> full offering
  const [chosen, setChosen] = useState({});                   // choice name -> [option ids]
  const [specs, setSpecs] = useState({});                     // spec id -> spec
  const [chars, setChars] = useState({});                     // characteristic name -> value
  const [avail, setAvail] = useState({});                     // offering id -> units | null (unmanaged)
  const [extras, setExtras] = useState({});                   // optional component id -> added?
  const [shot, setShot] = useState(0);                        // gallery index
  const [error, setError] = useState(null);

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
  const ownConfigurable = offering && !offering.isBundle
    && ((offering.category || [])[0] || {}).name === 'Devices';

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
      await addToCart(offering, selections, 1, ownChars);
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
              <select
                value={chars[characteristic.name] || ''}
                onChange={(e) => setChars((c) => ({ ...c, [characteristic.name]: e.target.value }))}
              >
                {(characteristic.productSpecCharacteristicValue || []).map((v) => (
                  <option key={v.value} value={v.value}>{v.value}</option>
                ))}
              </select>
            </label>
          ))}
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

      {allPrices.length > 0 && (
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
                  <td className="num">{monthly.value.toFixed(2)} {monthly.unit}</td>
                </tr>
              )}
            </tbody>
          </table>
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
      <button className="primary big" onClick={add} disabled={outOfStock || Boolean(unmetChoice)}>
        {outOfStock ? t('Out of stock') : t('Add to cart')}
      </button>
    </div>
  );
}
