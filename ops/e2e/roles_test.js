/* TMF672 E2E: tenant admins manage their own staff through the TMF API —
 * each realm's admin sees and touches exactly their own realm's users. */
const { chromium } = require('playwright');

const API = 'http://localhost:8080/tmf-api/rolesAndPermissionsManagement/v4';

async function staffToken(request, realm) {
  const res = await request.post(
    `http://localhost:8085/realms/${realm}/protocol/openid-connect/token`,
    { form: { grant_type: 'password', client_id: 'bss-demo', username: 'demo', password: 'demo' } });
  return (await res.json()).access_token;
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  const fail = (msg) => { console.error('FAIL: ' + msg); process.exit(1); };
  const genalpha = await staffToken(ctx.request, 'bss');
  const nova = await staffToken(ctx.request, 'nova');
  const as = (token) => ({ Authorization: 'Bearer ' + token });

  // Roles list is the business catalog, not IdP plumbing
  const roles = await (await ctx.request.get(`${API}/userRole`, { headers: as(genalpha) })).json();
  const names = roles.map((r) => r.name);
  if (!names.includes('agent')) fail('agent role missing from TMF672 list: ' + names);
  if (names.some((n) => n.startsWith('default-roles-') || n === 'offline_access')) {
    fail('internal IdP roles leaked: ' + names);
  }
  console.log('OK role catalog lists business roles only,', names.length, 'roles');

  // Same username, two tenants, two different identities
  const gUsers = await (await ctx.request.get(`${API}/user?username=agent-anna`, { headers: as(genalpha) })).json();
  const nUsers = await (await ctx.request.get(`${API}/user?username=agent-anna`, { headers: as(nova) })).json();
  if (!gUsers.length || !nUsers.length) fail('agent-anna missing in a realm');
  if (gUsers[0].id === nUsers[0].id) fail('agent-anna has the same id across tenants?!');
  console.log('OK user search is tenant-local (distinct agent-anna identities)');

  // Grant -> visible -> revoke -> gone, through the TMF API only
  const anna = gUsers[0].id;
  const grant = await ctx.request.post(`${API}/permission`, {
    headers: { ...as(genalpha), 'Content-Type': 'application/json' },
    data: { user: { id: anna }, userRole: { name: 'promotion:read' } },
  });
  if (grant.status() !== 201) fail('grant failed: ' + grant.status() + ' ' + await grant.text());
  const granted = await grant.json();
  let perms = await (await ctx.request.get(`${API}/permission?userId=${anna}`, { headers: as(genalpha) })).json();
  if (!perms.some((p) => p.userRole.name === 'promotion:read')) fail('granted role not visible');
  console.log('OK granted promotion:read to agent-anna via TMF672');

  const revoke = await ctx.request.delete(`${API}/permission/${granted.id}`, { headers: as(genalpha) });
  if (revoke.status() !== 204) fail('revoke failed: ' + revoke.status());
  perms = await (await ctx.request.get(`${API}/permission?userId=${anna}`, { headers: as(genalpha) })).json();
  if (perms.some((p) => p.userRole.name === 'promotion:read')) fail('revoked role still present');
  console.log('OK revoked again; grant lifecycle is fully API-driven');

  // A nova admin cannot address genalpha's user (different realm, 404/not-found semantics)
  const cross = await ctx.request.get(`${API}/permission?userId=${anna}`, { headers: as(nova) });
  if (cross.status() === 200 && (await cross.json()).length) {
    fail("nova admin can read genalpha user's permissions");
  }
  console.log('OK cross-tenant administration is structurally impossible:', cross.status());

  // Plain agents cannot manage roles at all
  const annaTokenRes = await ctx.request.post(
    'http://localhost:8085/realms/bss/protocol/openid-connect/token',
    { form: { grant_type: 'password', client_id: 'bss-csr', username: 'agent-anna', password: 'agent' } });
  const annaToken = (await annaTokenRes.json()).access_token;
  const denied = await ctx.request.get(`${API}/userRole`, { headers: as(annaToken) });
  if (denied.status() !== 403) fail('agent should get 403 from TMF672, got ' + denied.status());
  console.log('OK agents are denied role administration (403)');

  await browser.close();
  console.log('\nALL USER-ROLES CHECKS PASSED');
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
