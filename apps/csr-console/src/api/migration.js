// migration: part of the CSR console's TMF client (split from api.js; the barrel re-exports).
import { MIGRATION, authFetch, json } from './_http.js';

export async function migrationPlans() {
  return json(await authFetch(`${MIGRATION}/migrationPlan?limit=100`));
}

export async function migrationPlan(id) {
  return json(await authFetch(`${MIGRATION}/migrationPlan/${id}`));
}

/** {name, matrix: [{sourceOfferingId, targetOfferingId, deltaClass}],
 *  eligibility: {inBinding}, trigger: {type}, jurisdictionPack: {noticeDays}} */
export async function createMigrationPlan(body) {
  return json(await authFetch(`${MIGRATION}/migrationPlan`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }));
}

export async function attachMigrationSimulation(id, simulationRef) {
  return json(await authFetch(`${MIGRATION}/migrationPlan/${id}/attachSimulation`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ simulationRef }),
  }));
}

export async function armMigrationPlan(id) {
  return json(await authFetch(`${MIGRATION}/migrationPlan/${id}/arm`, { method: 'POST' }));
}

export async function pauseMigrationPlan(id) {
  return json(await authFetch(`${MIGRATION}/migrationPlan/${id}/pause`, { method: 'POST' }));
}

export async function resumeMigrationPlan(id) {
  return json(await authFetch(`${MIGRATION}/migrationPlan/${id}/resume`, { method: 'POST' }));
}

export async function migrationProgress(id) {
  return json(await authFetch(`${MIGRATION}/migrationPlan/${id}/progress`));
}

export async function migrationCustomers(id, state) {
  return json(await authFetch(`${MIGRATION}/migrationPlan/${id}/customer?limit=200${state ? `&state=${encodeURIComponent(state)}` : ''}`));
}

export async function exitMigrationCustomer(id, cid) {
  return json(await authFetch(`${MIGRATION}/migrationPlan/${id}/customer/${cid}/exit`, { method: 'POST' }));
}

export async function rollbackMigrationCustomer(id, cid) {
  return json(await authFetch(`${MIGRATION}/migrationPlan/${id}/customer/${cid}/rollback`, { method: 'POST' }));
}
