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
  const [chosen, setChosen] = useState({});                   // choice name -> option id
  const [specs, setSpecs] = useState({});                     // spec id -> spec
  const [chars, setChars] = useState({});                     // characteristic name -> value
  const [avail, setAvail] = useState({});                     // offering id -> units | null (unmanaged)
  const [extras, setExtras] = useState({});                   // optional component id -> added?
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
        // Resolve every choice option AND optional add-on to its full offering.
        const optionRefs = (o.bundledProductOffering || []).filter(isChoice).flatMap((c) => c.options);
        const optionalRefs = (o.bundledProductOffering || [])
          .filter((e) => !isChoice(e) && e.bundledProductOfferingOption?.numberRelOfferLowerLimit === 0);
        const toResolve = [...optionRefs, ...optionalRefs];
        const full = await Promise.all(toResolve.map((r) => getOffering(r.id)));
        setOptionOfferings(Object.fromEntries(full.map((f) => [f.id, f])));
        const defaults = {};
        for (const c of (o.bundledProductOffering || []).filter(isChoice)) {
          defaults[c.name] = c.default || c.options[0]?.id;
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
    () => Object.values(chosen).map((oid) => optionOfferings[oid]).filter(Boolean),
    [chosen, optionOfferings]);

  const activeCharacteristics = useMemo(() => selectedOptions.flatMap((option) => {
    const spec = specs[option.productSpecification?.id];
    return (spec?.productSpecCharacteristic || []).map((c) => ({ option, characteristic: c }));
  }), [selectedOptions, specs]);

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

  const addedExtras = optionalComponents
    .map((e) => (extras[e.id] ? optionOfferings[e.id] : null)).filter(Boolean);
  const own = pricesOf(offering, prices);
  const optionPrices = selectedOptions.flatMap((o) => pricesOf(o, prices));
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

  // The page is orderable unless a stock-managed part of it is gone.
  const relevantIds = [offering.id, ...Object.values(chosen)].filter(Boolean);
  const managed = relevantIds.filter((rid) => avail[rid] != null);
  const scarcest = managed.length ? Math.min(...managed.map((rid) => avail[rid])) : null;
  const outOfStock = scarcest === 0;

  async function add() {
    try {
      await addToCart(offering, selections);
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

      {fixed.length > 0 && (
        <>
          <h2>What's included</h2>
          <ul className="includes big">
            {fixed.map((c) => <li key={c.id}>{c.name}</li>)}
          </ul>
        </>
      )}

      {choices.map((choice) => (
        <div key={choice.name} className="choice">
          <h2>{choice.name}</h2>
          <div className="options">
            {choice.options.map((opt) => {
              const full = optionOfferings[opt.id];
              const optMonthly = full ? monthlyTotal(pricesOf(full, prices)) : null;
              return (
                <label key={opt.id} className={chosen[choice.name] === opt.id ? 'option on' : 'option'}>
                  <input
                    type="radio"
                    name={choice.name}
                    checked={chosen[choice.name] === opt.id}
                    onChange={() => setChosen((c) => ({ ...c, [choice.name]: opt.id }))}
                  />
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
      ))}

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
      <button className="primary big" onClick={add} disabled={outOfStock}>
        {outOfStock ? t('Out of stock') : t('Add to cart')}
      </button>
    </div>
  );
}
