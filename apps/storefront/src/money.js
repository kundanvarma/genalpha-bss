/** "39.99 EUR/month" from a productOfferingPrice; empty string when unpriced. */
export function fmtPrice(price) {
  if (price?.price?.value == null) return '';
  const money = `${price.price.value.toFixed(2)} ${price.price.unit || ''}`.trim();
  if (price.priceType === 'recurring' && price.recurringChargePeriodType) {
    return `${money}/${price.recurringChargePeriodType}`;
  }
  if (price.priceType === 'oneTime') {
    return `${money} one-time`;
  }
  return money;
}

/** Resolve an offering's price refs against the price index. */
export function pricesOf(offering, index) {
  return (offering.productOfferingPrice || [])
    .map((ref) => index[ref.id])
    .filter(Boolean);
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
