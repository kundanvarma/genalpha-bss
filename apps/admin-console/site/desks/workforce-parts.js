/* Workforce — the operator's two levers: the crew ceiling and hiring. */
'use strict';

// THE CONTROLS: the operator's two levers, next to the numbers they
// govern — the crew ceiling (surge staffing never grows past it) and
// hiring (mint a badge for a chosen job; ACTIVATING the worker is the
// runtime's act — the instructions say exactly how).
function workforceControls(staffing) {
  const controls = document.createElement('div');
  controls.style.cssText = 'display:flex;flex-wrap:wrap;gap:0.7rem;margin:0.4rem 0 0.4rem';

  const ceilBox = document.createElement('div');
  ceilBox.style.cssText = 'flex:1 1 16rem;padding:0.8rem 1rem;border:1px solid var(--line,#ddd);border-radius:10px;background:var(--card,#fafafa)';
  const ceilHead = document.createElement('b');
  ceilHead.textContent = 'Crew ceiling';
  const ceilRow = document.createElement('div');
  ceilRow.style.cssText = 'display:flex;gap:0.5rem;align-items:center;margin-top:0.4rem';
  const ceilInput = document.createElement('input');
  ceilInput.type = 'number';
  ceilInput.min = '0';
  ceilInput.value = staffing.maxWorkers ?? 0;
  ceilInput.dataset.testid = 'wf-ceiling-input';
  ceilInput.style.width = '5rem';
  const ceilSave = document.createElement('button');
  ceilSave.textContent = 'Save';
  ceilSave.dataset.testid = 'wf-ceiling-save';
  const ceilNote = document.createElement('div');
  ceilNote.style.cssText = 'font-size:0.8rem;color:var(--dim,#777);margin-top:0.35rem';
  ceilNote.textContent = `0 = unlimited · a new worker past the ceiling is refused at claim time`
    + ` · surge threshold: ${staffing.surgeThresholdOpenPerWorker ?? '—'} open tasks per worker`;
  ceilSave.addEventListener('click', async () => {
    ceilSave.disabled = true;
    const res = await authFetch('/ai/v1/governance/budget', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ maxWorkers: Number(ceilInput.value) || 0 }),
    });
    ceilSave.disabled = false;
    ceilNote.textContent = res.ok
      ? `Saved — the ceiling is now ${Number(ceilInput.value) || 0 || 'unlimited'}`
      : 'Could not save: ' + res.status;
    if (res.ok) renderWorkforce();
  });
  ceilRow.append(ceilInput, ceilSave);
  ceilBox.append(ceilHead, ceilRow, ceilNote);

  const hireBox = document.createElement('div');
  hireBox.style.cssText = 'flex:2 1 22rem;padding:0.8rem 1rem;border:1px solid var(--line,#ddd);border-radius:10px;background:var(--card,#fafafa)';
  const hireHead = document.createElement('b');
  hireHead.textContent = 'Hire a worker';
  const hireRow = document.createElement('div');
  hireRow.style.cssText = 'display:flex;gap:0.5rem;align-items:center;margin-top:0.4rem;flex-wrap:wrap';
  const hireName = document.createElement('input');
  hireName.placeholder = 'name (e.g. hermes-2)';
  hireName.dataset.testid = 'wf-hire-name';
  const hireJob = document.createElement('select');
  hireJob.dataset.testid = 'wf-hire-job';
  for (const [v, label] of [['care', 'Care (tickets)'], ['back-office', 'Back-office (cash)'],
    ['generalist', 'Generalist (both)']]) {
    const o = document.createElement('option');
    o.value = v;
    o.textContent = label;
    hireJob.append(o);
  }
  const hireGo = document.createElement('button');
  hireGo.textContent = 'Hire';
  hireGo.dataset.testid = 'wf-hire-go';
  const hireOut = document.createElement('div');
  hireOut.style.cssText = 'font-size:0.85rem;margin-top:0.5rem';
  hireOut.textContent = 'With the workforce package deployed, Hire mints the badge AND starts the '
    + 'worker container — no credentials to handle. Without it: badge + once-shown credentials, '
    + 'and the worker starts when its runtime runs the matching job card.';
  hireOut.style.color = 'var(--dim,#777)';
  const JOB_CARDS = {
    care: 'skills/care-triage — schedule "Work the care queue" every 15 min',
    'back-office': 'skills/cash-matching — schedule "Work the AR queue" daily',
    generalist: 'both job cards: care-triage (15 min) + cash-matching (daily)',
  };
  hireGo.addEventListener('click', async () => {
    const name = (hireName.value || 'worker').trim().toLowerCase().replace(/[^a-z0-9-]/g, '');
    hireGo.disabled = true;
    // THE CLOSED LOOP: when the workforce package's controller is deployed,
    // Hire = badge minted + worker container STARTED, credentials never
    // shown to anyone. Without it, fall back to the credentials flow.
    try {
      const ctl = await authFetch('/workforce-runtime/workers', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, job: hireJob.value }),
      });
      if (ctl.ok) {
        const started = await ctl.json();
        hireGo.disabled = false;
        hireOut.replaceChildren();
        hireOut.style.color = 'inherit';
        const done = document.createElement('div');
        done.dataset.testid = 'wf-hired-started';
        done.textContent = `Hired AND started: ${started.badge} (container ${started.container}, `
          + `${started.job}). It will appear on the crew after its first claim. Fire = the ✖ on its row.`;
        hireOut.append(done);
        setTimeout(renderWorkforce, 4000);
        return;
      }
    } catch { /* controller not deployed — fall through */ }
    const minted = await authFetch('/tmf-api/rolesAndPermissionsManagement/v4/user', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email: `worker-${hireJob.value}-${name}-${Date.now()}@bss.local`,
        givenName: name, familyName: hireJob.value,
      }),
    });
    if (!minted.ok) {
      hireGo.disabled = false;
      hireOut.textContent = 'Hiring failed: ' + minted.status + ' (roles:admin required)';
      return;
    }
    const user = await minted.json();
    const grant = await authFetch('/tmf-api/rolesAndPermissionsManagement/v4/permission', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ user: { id: user.id }, userRole: { name: 'digital-worker' } }),
    });
    hireGo.disabled = false;
    if (!grant.ok) {
      hireOut.textContent = 'Badge grant failed: ' + grant.status;
      return;
    }
    hireOut.replaceChildren();
    hireOut.style.color = 'inherit';
    const creds = document.createElement('div');
    creds.dataset.testid = 'wf-hired-creds';
    creds.dataset.userid = user.id;
    creds.style.cssText = 'font-family:ui-monospace,monospace;background:var(--bg,#fff);border:1px solid var(--line,#ddd);border-radius:6px;padding:0.5rem 0.7rem;margin-bottom:0.35rem';
    creds.textContent = `BSS_WORKER_USERNAME=${user.username}\nBSS_WORKER_PASSWORD=${user.temporaryPassword}`;
    creds.style.whiteSpace = 'pre';
    const next = document.createElement('div');
    next.style.cssText = 'font-size:0.82rem;color:var(--dim,#777)';
    next.textContent = `Shown ONCE — hand to the runtime (integrations/hermes-worker/config.snippet.yaml), `
      + `then activate: ${JOB_CARDS[hireJob.value]}. The type on the crew list stays OBSERVED from its work.`;
    hireOut.append(creds, next);
  });
  hireRow.append(hireName, hireJob, hireGo);
  hireBox.append(hireHead, hireRow, hireOut);
  // ceiling + hiring are GOVERNANCE (ai:admin) — the approvals queue below
  // stays operational; the server refuses non-admins either way
  const wfRoles = (tokenClaims().realm_access || {}).roles || [];
  if (wfRoles.includes('ai:admin')) {
    controls.append(ceilBox, hireBox);
  } else {
    const dim = document.createElement('p');
    dim.className = 'dim';
    dim.style.cssText = 'font-size:0.85rem';
    dim.textContent = 'Crew ceiling and hiring need ai:admin — you can watch the crew and approve its work.';
    controls.append(dim);
  }
  return controls;
}
