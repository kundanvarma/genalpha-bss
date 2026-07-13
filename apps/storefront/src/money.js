import { locale, money as intlMoney, t } from './i18n.js';

/**
 * "39.99 EUR/month" from a productOfferingPrice; empty string when unpriced.
 * English keeps its historical format; other locales get proper Intl
 * formatting ("kr 299,00/md." in Norway) from the price's own unit.
 */
export function fmtPrice(price) {
  if (price?.price?.value == null) return '';
  const money = locale === 'en'
    ? `${price.price.value.toFixed(2)} ${price.price.unit || ''}`.trim()
    : intlMoney(price.price.value, price.price.unit);
  if (price.priceType === 'recurring' && price.recurringChargePeriodType) {
    return `${money}/${t(price.recurringChargePeriodType)}`;
  }
  if (price.priceType === 'oneTime') {
    return `${money} ${t('one-time')}`;
  }
  return money;
}

/** "{value} {unit}/month" in English; "kr 299,00/md." elsewhere. */
export function fmtMonthly(total) {
  if (!total) return '';
  const money = locale === 'en'
    ? `${total.value.toFixed(2)} ${total.unit}`
    : intlMoney(total.value, total.unit);
  return `${money}/${t('month')}`;
}

/**
 * TMF620 prodSpecCharValueUse: a price conditioned on characteristic values
 * ("+2.00/month when color = Titanium Edition") applies only when every
 * condition matches the current picks. Unconditioned prices always apply;
 * conditioned ones never apply when no picks are known (list views show the
 * base price).
 */
export function priceApplies(price, characteristics) {
  const conditions = price.prodSpecCharValueUse;
  if (!conditions || !conditions.length) return true;
  if (!characteristics) return false;
  return conditions.every((c) => {
    const allowed = (c.productSpecCharacteristicValue || []).map((v) => v.value);
    return characteristics[c.name] != null && allowed.includes(characteristics[c.name]);
  });
}

/** Resolve an offering's price refs against the price index — the price
 * follows the pick when characteristics are given. */
export function pricesOf(offering, index, characteristics = null) {
  return (offering.productOfferingPrice || [])
    .map((ref) => index[ref.id])
    .filter(Boolean)
    .filter((p) => priceApplies(p, characteristics));
}

/** Total monthly recurring charge across resolved prices (bundle headline). */
export function monthlyTotal(prices) {
  const monthly = prices.filter((p) => p.priceType === 'recurring' && p.price?.value != null);
  if (!monthly.length) return null;
  const total = monthly.reduce((sum, p) => sum + p.price.value, 0);
  return { value: total, unit: monthly[0].price.unit || 'EUR' };
}

/** Total one-time charge across resolved prices (what is due at checkout). */
export function oneTimeTotal(prices) {
  const once = prices.filter((p) => p.priceType === 'oneTime' && p.price?.value != null);
  if (!once.length) return null;
  const total = once.reduce((sum, p) => sum + p.price.value, 0);
  return { value: total, unit: once[0].price.unit || 'EUR' };
}
