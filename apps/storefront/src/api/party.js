// party: part of the storefront's TMF client (split from api.js; the barrel re-exports).
import { COMM, GEO, ORDERING, PARTY, authFetch, json, tokenClaims } from './_http.js';

/**
 * First sign-in provisions the TMF632 individual; the backend keys it to the
 * token subject, so repeating this is a no-op.
 */
export async function ensureParty() {
  const claims = tokenClaims();
  const email = claims.email || claims.preferred_username;
  return json(await authFetch(`${PARTY}/individual`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      givenName: claims.given_name || claims.preferred_username || 'Customer',
      familyName: claims.family_name || '—',
      // The email rides along so assisted channels can identify the customer
      // by something a human recognizes, not a UUID.
      ...(email && email.includes('@') ? {
        contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }],
      } : {}),
    }),
  }));
}

export async function myParty() {
  const claims = tokenClaims();
  return json(await authFetch(`${PARTY}/individual/${claims.sub}`));
}

/** Registry-verify a delivery address for the signed-in shopper (freg F-P2):
 * TMF673 validation with the party context — the backend asks the delivery
 * country's national registry and answers match / mismatch / no_data /
 * unavailable. Returns the registryMatch part, or null (postal-wash only —
 * e.g. no registry for that country, or the address failed validation). */
export async function verifyDeliveryAddress(address, partyName) {
  const result = await json(await authFetch(`${GEO}/geographicAddressValidation`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ submittedGeographicAddress: address, relatedParty: { name: partyName } }),
  }));
  return result.registryMatch || null;
}

// ---------------- household billing (person-payer, with consent) ----------------

export async function myHousehold() {
  const claims = tokenClaims();
  return json(await authFetch(`${PARTY}/individual/${claims.sub}/household`));
}

export async function requestHouseholdPayer(payerEmail) {
  const claims = tokenClaims();
  return json(await authFetch(`${PARTY}/individual/${claims.sub}/householdPayer`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ payerEmail }),
  }));
}

/** The payer invites an EXISTING customer into the family — the member
 * accepts (or not) from their own Family page. Consent, mirrored. */
export async function inviteFamilyMember(memberEmail) {
  return json(await authFetch(`${PARTY}/individual/${tokenClaims().sub}/householdInvite`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ memberEmail }),
  }));
}

export async function acceptFamilyInvite() {
  return json(await authFetch(`${PARTY}/individual/${tokenClaims().sub}/householdInvite/accept`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }));
}

export async function acceptDependent(dependentId) {
  return json(await authFetch(`${PARTY}/individual/${dependentId}/householdPayer/accept`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }));
}

export async function endHouseholdLink(dependentId) {
  return json(await authFetch(`${PARTY}/individual/${dependentId}/householdPayer`, {
    method: 'DELETE',
  }));
}

/** CHILD ACCOUNT: the payer mints the kid's login (TMF672, customer role
 * only) and creates their party record with the household link born active.
 * The temporary password is returned ONCE, for hand-over. */
export async function addFamilyMember(givenName, familyName, email, birthDate) {
  const login = await json(await authFetch('/tmf-api/rolesAndPermissionsManagement/v4/user', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, givenName, familyName }),
  }));
  const claims = tokenClaims();
  await json(await authFetch(`${PARTY}/individual/${claims.sub}/dependents`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      id: login.id, givenName, familyName,
      ...(birthDate ? { birthDate } : {}),
      contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }],
    }),
  }));
  return { id: login.id, email, temporaryPassword: login.temporaryPassword };
}

/** The payer orders FOR a dependent — ordering verifies the live link and
 * stamps the payer; the plan lands on the payer's bill. */
export async function orderForDependent(offering, dependentId) {
  return json(await authFetch(`${ORDERING}/productOrder`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      productOrderItem: [{ action: 'add',
        productOffering: { id: offering.id, name: offering.name } }],
      relatedParty: [{ id: dependentId, role: 'customer' }],
    }),
  }));
}

export async function updateMyParty(patch) {
  const claims = tokenClaims();
  return json(await authFetch(`${PARTY}/individual/${claims.sub}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  }));
}

/** My marketing preference — { marketingOptOut }. */
export async function myMarketingPreference() {
  return json(await authFetch(`${COMM}/marketingPreference`));
}

/** Set my marketing preference; opting out stops marketing (in-app + email). */
export async function setMarketingPreference(optOut) {
  return json(await authFetch(`${COMM}/marketingPreference`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ optOut }),
  }));
}
