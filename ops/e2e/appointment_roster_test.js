/* Installation scheduling is the TENANT's (suite #112): the calendar (zone,
 * working days, windows) is per operator, and window capacity is DERIVED
 * from a technician roster — no roster, flat default. Runs against the
 * enet tenant (America/Guyana) so the zone test is real, and cleans up
 * its own technicians.
 */
const API = 'http://localhost:8080';
const KC = 'http://localhost:8085/realms/enet/protocol/openid-connect/token';
const A = '/tmf-api/appointment/v4';
const run = Date.now();
const fail = (m) => { throw new Error(m); };

async function token(user, pass) {
  const r = await fetch(KC, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: 'bss-demo', username: user, password: pass }) });
  if (!r.ok) fail(`token(${user}): ${r.status}`);
  return (await r.json()).access_token;
}
async function call(method, path, tok, body, host) {
  // a guest's tenant is the HOSTNAME (fetch drops a hand-set Host header, so hit the host itself)
  const base = host ? `http://${host}:8080` : API;
  const r = await fetch(base + path, { method,
    headers: { ...(tok ? { Authorization: `Bearer ${tok}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch {}
  return { status: r.status, body: json, text };
}

(async () => {
  const staff = await token('demo', 'demo');
  const devi = await token('devi@enet.example', 'devi');
  const made = [];
  try {
    /* 1. the calendar is the tenant's: Georgetown, and a guest on the enet host sees it */
    const cfg = await call('GET', `${A}/scheduleConfig`, staff);
    if (cfg.status !== 200) fail(`scheduleConfig: ${cfg.status} ${cfg.text.slice(0, 120)}`);
    if (cfg.body.timezone !== 'America/Guyana') fail(`enet calendar zone: ${cfg.body.timezone}`);
    const guest = await call('POST', `${A}/searchTimeSlot`, null, {}, 'shop.enet.localhost');
    if (guest.status !== 201) fail(`guest search: ${guest.status}`);
    const windows = guest.body.availableTimeSlot || [];
    if (!windows.length) fail('no windows for enet');
    if (guest.body.timezone !== 'America/Guyana') fail(`search timezone: ${guest.body.timezone}`);
    for (const w of windows) {
      const s = w.validFor.startDateTime;
      if (!s.endsWith('-04:00')) fail(`window not in Guyana offset: ${s}`);
      if (!cfg.body.slotStarts.some((t) => s.includes('T' + t))) fail(`window start not configured: ${s}`);
      if (new Date(s).getUTCDay() === 0 && !cfg.body.workingDays.includes('SUN')) fail(`Sunday offered: ${s}`);
    }
    if (windows.some((w) => typeof w.remaining !== 'number')) fail('windows carry no remaining count');
    console.log(`  calendar: ${cfg.body.timezone} ${cfg.body.workingDays.join('/')} ${cfg.body.slotStarts.join(',')} · ${windows.length} windows · mode ${cfg.body.capacityMode}`);

    /* 2. customers cannot see or edit the roster */
    if ((await call('GET', `${A}/technician`, devi)).status !== 403) fail('customer read the roster');
    if ((await call('PUT', `${A}/scheduleConfig`, devi, { daysAhead: 3 })).status !== 403) fail('customer edited the calendar');

    /* 3. capacity follows the roster: a one-hour night-shift tech adds exactly one visit
     *    to a window nobody else covers — proven by a configured window only she works */
    const before = (await call('GET', `${A}/technician`, staff)).body || [];
    const nightOnly = await call('POST', `${A}/technician`, staff, {
      name: `Night Tech ${run}`, zone: 'Linden', workingDays: ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'],
      startTime: '20:00', endTime: '23:00', skills: ['fibre'] });
    if (nightOnly.status !== 201) fail(`add technician: ${nightOnly.status} ${nightOnly.text.slice(0, 120)}`);
    made.push(nightOnly.body.id);
    // temporarily open a 20:00 window on the calendar; only the night tech covers it
    const saved = await call('PUT', `${A}/scheduleConfig`, staff, { slotStarts: [...cfg.body.slotStarts, '20:00'] });
    if (saved.status !== 200) fail(`calendar save: ${saved.status} ${saved.text.slice(0, 120)}`);
    try {
      const after = await call('POST', `${A}/searchTimeSlot`, devi, {});
      const night = (after.body.availableTimeSlot || []).filter((w) => w.validFor.startDateTime.includes('T20:00'));
      if (!night.length) fail('20:00 window not offered although a technician covers it');
      if (night.some((w) => w.remaining !== 1)) fail(`night window capacity should be 1 (one tech): ${JSON.stringify(night[0])}`);
      // and a day window's remaining equals its roster coverage minus bookings (>=1 with the seeded roster)
      const day = (after.body.availableTimeSlot || []).find((w) => w.validFor.startDateTime.includes('T08:00'));
      if (day && day.remaining < 1) fail('day window has no remaining despite active roster');
      // off duty: the night window disappears entirely
      const off = await call('PATCH', `${A}/technician/${nightOnly.body.id}`, staff, { active: false });
      if (off.status !== 200 || off.body.active !== false) fail(`off duty: ${off.status}`);
      const gone = await call('POST', `${A}/searchTimeSlot`, devi, {});
      if ((gone.body.availableTimeSlot || []).some((w) => w.validFor.startDateTime.includes('T20:00'))) {
        fail('20:00 window still offered with its only technician off duty');
      }
      // and a by-hand booking into it is refused: nobody works it
      const start = night[0].validFor.startDateTime;
      const refused = await call('POST', `${A}/appointment`, devi, { validFor: night[0].validFor });
      if (refused.status !== 409) fail(`booking an uncovered window should 409, got ${refused.status} (${start})`);
      console.log(`  roster: +1 night tech → 20:00 window remaining 1; off duty → window gone, booking 409 · roster size ${before.length}`);
    } finally {
      await call('PUT', `${A}/scheduleConfig`, staff, { slotStarts: cfg.body.slotStarts });
    }

    /* 4. THE SEAM: point enet at its own workforce system (mock-fsm, TMF646) —
     *    the shop now shows THAT calendar (half-hour starts, its zone quota), a
     *    booking carries the FSM's id, cancel propagates, and back to roster. */
    const seam = await call('PUT', `${A}/scheduleConfig`, staff, { provider: 'tmf646', providerUrl: 'http://mock-fsm:8080', providerCategory: 'fibre-install' });
    if (seam.status !== 200 || seam.body.capacityMode !== 'provider') fail(`switch provider: ${seam.status} ${seam.text.slice(0, 160)}`);
    try {
      const probe = await call('POST', `${A}/scheduleConfig/test`, staff, {});
      if (probe.status !== 200 || probe.body.ok !== true) fail(`provider probe: ${probe.status} ${probe.text.slice(0, 160)}`);
      const ext = await call('POST', `${A}/searchTimeSlot`, null, { relatedPlace: { role: 'installation', postCode: '4131519', city: 'Georgetown' },
        relatedEntity: [{ id: 'x', name: 'OnFiber 350', '@referredType': 'ProductOffering' }] }, 'shop.enet.localhost');
      if (ext.status !== 201 || ext.body.provider !== 'tmf646') fail(`external search: ${ext.status} ${ext.text.slice(0, 160)}`);
      const ew = ext.body.availableTimeSlot || [];
      if (!ew.length || !ew.every((w) => /T\d\d:30/.test(w.validFor.startDateTime))) fail(`expected the FSM's half-hour windows, got ${JSON.stringify(ew.slice(0, 2))}`);
      const bartica = await call('POST', `${A}/searchTimeSlot`, null, { relatedPlace: { role: 'installation', postCode: '7010101', city: 'Bartica' } }, 'shop.enet.localhost');
      if (!(bartica.body.availableTimeSlot || []).every((w) => w.remaining === 1)) fail('FSM zone quota not honoured (Bartica should be 1 crew)');
      const bk = await call('POST', `${A}/appointment`, devi, { validFor: ew[0].validFor, description: 'seam proof',
        relatedEntity: [{ id: `order-${run}`, '@referredType': 'ProductOrder' }], place: { postCode: '4131519', city: 'Georgetown' } });
      if (bk.status !== 201 || !/^FSM-/.test(bk.body.externalId || '')) fail(`booking via FSM: ${bk.status} ${bk.text.slice(0, 160)}`);
      const fsmView = await fetch(`http://localhost:8151/appointment/${bk.body.externalId}`).then((r) => r.json());
      if (fsmView.status !== 'confirmed') fail('FSM does not hold the booking');
      const cx = await call('PATCH', `${A}/appointment/${bk.body.id}`, devi, { status: 'cancelled' });
      if (cx.status !== 200) fail(`cancel: ${cx.status}`);
      const fsmAfter = await fetch(`http://localhost:8151/appointment/${bk.body.externalId}`).then((r) => r.json());
      if (fsmAfter.status !== 'cancelled') fail('cancel did not propagate to the FSM');
      console.log(`  seam: enet → mock-fsm (TMF646): ${ew.length} FSM windows, Bartica quota 1, booked ${bk.body.externalId}, cancel propagated`);
    } finally {
      const back = await call('PUT', `${A}/scheduleConfig`, staff, { provider: 'roster' });
      if (back.status !== 200) fail(`restore roster: ${back.status}`);
    }

    /* 5. validation is honest */
    if ((await call('PUT', `${A}/scheduleConfig`, staff, { timezone: 'Mars/Olympus' })).status !== 400) fail('bad zone accepted');
    if ((await call('POST', `${A}/technician`, staff, { name: 'x', startTime: '17:00', endTime: '08:00' })).status !== 400) fail('inverted shift accepted');
    console.log('PASS appointment_roster_test');
  } finally {
    for (const id of made) await call('DELETE', `${A}/technician/${id}`, staff);
  }
})().catch((e) => { console.error('FAIL', e.message); process.exit(1); });
