'use strict';

/* ---------------- Staff tab: TMF672 over the tenant's own IdP ---------------- */
/* A tenant admin manages what each operator can do, in business terms (areas),
 * without ever opening the identity provider. Grants and revokes go through
 * the user-roles component, which translates to the tenant's IdP behind its
 * admin seam. */
const ROLES_BASE = '/tmf-api/rolesAndPermissionsManagement/v4';
const AREAS = [
  { name: 'Product catalog', hint: 'offerings, prices, stock, serviceability',
    roles: ['catalog:read', 'catalog:write', 'stock:read', 'stock:write', 'qualification:read', 'qualification:write'] },
  { name: 'Rules & pricing', hint: 'business rules, dynamic pricing',
    roles: ['policy:read', 'policy:write'] },
  { name: 'Marketing', hint: 'campaigns and promo codes',
    roles: ['campaign:read', 'campaign:write', 'promotion:read', 'promotion:write'] },
  { name: 'Customer bills', hint: 'back-office bill view',
    roles: ['billing:admin'] },
  { name: 'Appointments', hint: 'back-office appointment view',
    roles: ['appointment:admin'] },
  { name: 'Number porting', hint: 'port-in/out management',
    roles: ['porting:read', 'porting:write'] },
  { name: 'CSR powers', hint: 'fulfil orders, cease services',
    roles: ['service:read', 'service:write', 'ordering:write'] },
  { name: 'AI tools', hint: 'copilot, drafting, audit view',
    roles: ['ai:use'] },
  { name: 'Staff administration', hint: 'this tab',
    roles: ['roles:admin'] },
];

function staffPanel() {
  let panel = document.getElementById('staff-panel');
  if (!panel) {
    panel = document.createElement('div');
    panel.id = 'staff-panel';
    document.querySelector('.table-wrap').after(panel);
  }
  panel.hidden = false;
  return panel;
}


async function renderStaff() {
  const panel = staffPanel();
  panel.replaceChildren();
  const bar = document.createElement('div');
  bar.className = 'staffbar';
  const q = document.createElement('input');
  q.placeholder = 'Search staff by username/email…';
  q.id = 'staff-q';
  const go = document.createElement('button');
  go.className = 'primary';
  go.textContent = 'Search';
  go.id = 'staff-search';
  bar.append(q, go);
  const results = document.createElement('div');
  results.id = 'staff-results';
  const detail = document.createElement('div');
  detail.id = 'staff-detail';
  panel.append(bar, results, detail);

  const search = async () => {
    results.textContent = 'Searching…';
    detail.replaceChildren();
    const res = await authFetch(`${ROLES_BASE}/user?username=${encodeURIComponent(q.value.trim())}`);
    const users = await res.json();
    results.replaceChildren(...users.map((u) => {
      const row = document.createElement('button');
      row.type = 'button';
      row.className = 'staffrow';
      row.textContent = `${u.username}`;
      row.addEventListener('click', () => renderStaffUser(u, detail));
      return row;
    }));
    if (!users.length) results.textContent = 'No staff found.';
  };
  go.addEventListener('click', search);
  q.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); search(); } });
}

async function renderStaffUser(user, detail) {
  detail.textContent = 'Loading permissions…';
  const perms = await (await authFetch(`${ROLES_BASE}/permission?userId=${user.id}`)).json();
  const held = new Map(perms.map((p) => [p.userRole.name, p.id]));   // role -> permission id
  detail.replaceChildren();
  const head = document.createElement('h3');
  head.textContent = user.username;
  head.className = 'staffname';
  const status = document.createElement('span');
  status.className = 'staffstatus';
  head.append(status);
  detail.append(head);
  for (const area of AREAS) {
    const row = document.createElement('label');
    row.className = 'staffarea';
    const box = document.createElement('input');
    box.type = 'checkbox';
    box.dataset.area = area.name;
    box.checked = area.roles.every((r) => held.has(r));
    box.addEventListener('change', async () => {
      box.disabled = true;
      status.textContent = ' saving…';
      try {
        if (box.checked) {
          for (const role of area.roles.filter((r) => !held.has(r))) {
            const res = await authFetch(`${ROLES_BASE}/permission`, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ user: { id: user.id }, userRole: { name: role } }),
            });
            const created = await res.json();
            held.set(role, created.id);
          }
        } else {
          for (const role of area.roles.filter((r) => held.has(r))) {
            await authFetch(`${ROLES_BASE}/permission/${held.get(role)}`, { method: 'DELETE' });
            held.delete(role);
          }
        }
        status.textContent = ' ✓ saved — takes effect on their next sign-in';
      } catch (e) {
        status.textContent = ' ✗ ' + e.message;
        box.checked = !box.checked;
      }
      box.disabled = false;
    });
    const text = document.createElement('span');
    text.innerHTML = `<b>${area.name}</b> <span class="dimhint">${area.hint}</span>`;
    row.append(box, text);
    detail.append(row);
  }
}
