/*
 * TMF Open API client, same-origin through the gateway. Split by domain under
 * src/api/ (one module per business area, none over the 300-line rule);
 * this file re-exports every function so callers import what they always did.
 * The backends enforce party scoping; this client never filters by party.
 */
export * from './api/catalog.js';
export * from './api/party.js';
export * from './api/orders.js';
export * from './api/services.js';
export * from './api/plans.js';
export * from './api/loyalty.js';
export * from './api/bills.js';
export * from './api/qualification.js';
export * from './api/care.js';
export * from './api/numbers.js';
export * from './api/growth.js';
export * from './api/devices.js';
export * from './api/controls.js';
