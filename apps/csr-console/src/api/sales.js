// sales: part of the CSR console's TMF client (split from api.js; the barrel re-exports).
import { ORDERING, authFetch, json } from './_http.js';

/** UPSELL, acted on: order the suggested offering ON BEHALF of the
 * customer (with their say-so on the line) — the agent is unscoped, so
 * the relatedParty in the body names the owner. */
export async function orderForCustomer(customerId, offering, characteristics = null, quantity = 1) {
  const item = { id: '1', action: 'add', quantity: quantity || 1,
    productOffering: { id: offering.id, name: offering.name } };
  if (characteristics && Object.keys(characteristics).length) {
    item.product = { productCharacteristic: Object.entries(characteristics).map(([name, value]) => ({ name, value: String(value) })) };
  }
  return json(await authFetch(`${ORDERING}/productOrder`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      productOrderItem: [item],
      relatedParty: [{ id: customerId, role: 'customer', '@referredType': 'Individual' }],
    }),
  }));
}

/** TMF760 — the one oracle: the space of an offering, and the verdict + price for picks. The desk renders it. */
export async function queryConfiguration(offeringId) {
  const res = await authFetch('/tmf-api/productConfigurationManagement/v5/queryProductConfiguration', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ productConfiguration: { productOffering: { id: offeringId } } }) });
  if (!res.ok) return null;
  return ((await res.json()).computedProductConfigurationItem || [])[0] || null;
}

export async function checkConfiguration(offeringId, characteristics = {}, quantity = 1) {
  const res = await authFetch('/tmf-api/productConfigurationManagement/v5/checkProductConfiguration', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ checkProductConfigurationItem: [{ id: '1', productConfiguration: { productOffering: { id: offeringId }, quantity,
      configurationCharacteristic: Object.entries(characteristics).filter(([, v]) => v != null && v !== '').map(([name, value]) => ({ name, value: String(value) })) } }] }) });
  if (!res.ok) return null;
  return ((await res.json()).checkProductConfigurationItem || [])[0] || null;
}

/** Or send the offer instead: a personal message that lands in the inbox
 * (and the ESP, and the interaction timeline — the whole omnichannel loop). */
export async function sendOffer(customerId, offering, agentName) {
  return json(await authFetch('/tmf-api/communicationManagement/v4/communicationMessage', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      subject: `An offer picked for you: ${offering.name}`,
      content: `${agentName || 'Your agent'} thought ${offering.name} fits how you use your services. `
        + 'Find it in the shop, or reply to this message and we will set it up.',
      relatedParty: [{ id: customerId, role: 'customer', '@referredType': 'Individual' }],
    }),
  }));
}
