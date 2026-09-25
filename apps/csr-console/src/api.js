/*
 * TMF Open API client, same-origin through the gateway. Split by domain under
 * src/api/ (one module per business area, none over the 300-line rule);
 * this file re-exports every function so callers import what they always did.
 * The backends enforce party scoping; this client never filters by party.
 */
export * from './api/customers.js';
export * from './api/holdings.js';
export * from './api/assist.js';
export * from './api/lines.js';
export * from './api/billing.js';
export * from './api/sales.js';
export * from './api/devices.js';
export * from './api/migration.js';
export * from './api/controls.js';
export * from './api/copilots.js';
