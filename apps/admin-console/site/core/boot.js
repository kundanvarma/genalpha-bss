/* Boot: sign in, the door gate, wire the frame, first page. Loads last so every desk above is defined; home.js and palette.js follow. */
'use strict';

async function main() {
  const ready = await ensureSignedIn().catch((e) => {
    el('signin').hidden = false;
    el('signin').firstElementChild.textContent = 'Sign-in failed: ' + e.message;
    return false;
  });
  if (!ready) {
    el('signin').hidden = false;
    return;
  }
  el('main').hidden = false;
  computeVisible();
  // DOOR GATE (#199, hardened): same-realm SSO can carry a SHOP CUSTOMER session
  // into this staff portal. A customer holds only baseline roles — refuse them
  // AT THE DOOR, before any tab or data renders, whatever their baseline roles
  // would otherwise "match".
  if (!isStaff()) {
    const who = tokenClaims().preferred_username || 'this account';
    el('tabs').textContent = '';
    el('username').textContent = '';
    const note = document.createElement('div');
    note.style.cssText = 'padding:1rem;max-width:34rem';
    note.dataset.testid = 'wrong-persona';
    const pEl = document.createElement('p');
    pEl.innerHTML = `Signed in as <b></b> — carried over from the shop by single sign-on. `
      + 'This is the staff back office, and this is a customer account. Nothing here is yours to see.';
    pEl.querySelector('b').textContent = who;
    const btn = document.createElement('button');
    btn.textContent = 'Switch to a staff account';
    btn.dataset.testid = 'switch-account';
    btn.addEventListener('click', () => {
      sessionStorage.setItem('bss.console.forceLogin', '1');
      signOut();
    });
    note.append(pEl, btn);
    el('tabs').append(note);
    el('logout').hidden = false;
    el('logout').addEventListener('click', signOut);
    return;
  }
  if (!visible.length) {
    el('tabs').textContent = 'Your account has no back-office areas — ask an admin for a role.';
    return;
  }
  const savedTab = sessionStorage.getItem('bss.console.tab');
  active = visible.find((r) => r.path === savedTab) || visible[0];
  el('username').textContent = tokenClaims().preferred_username || '';
  el('logout').hidden = false;
  el('logout').addEventListener('click', signOut);
  el('editor').addEventListener('focusin', (e) => {
    const name = e.target && e.target.name; if (!name || !active) return;
    if (!DESK.started || DESK.started.form !== active.path) { DESK.started = { form: active.path, submitted: false }; desk('form.start', active.path); }
    desk('form.field', active.path, { field: name });
  });
  el('editor').addEventListener('submit', deskOnSubmit);
  el('editor').addEventListener('submit', save);
  desk('desk.tabs', 'console', { tabs: RESOURCES.map((r) => r.path) });
  el('cancel-edit').addEventListener('click', stopEditing);
  el('prev').addEventListener('click', () => { offset = Math.max(0, offset - PAGE_SIZE); loadList(); });
  el('next').addEventListener('click', () => { offset += PAGE_SIZE; loadList(); });
  renderTabs();
  loadList();
}

main();
