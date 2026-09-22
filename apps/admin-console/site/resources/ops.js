/* Resources 3/9 — processes, runbooks, serviceability, wholesale stubs, risk, orders, appointments. */
'use strict';

RESOURCES.push(
  {
    path: 'processFlow',
    base: PROCESS_BASE,
    title: 'Process flows',
    readOnly: true,
    // TMF701 run-time: every order flow inspectable, STUCK as a state.
    // Specs (design intent, allowances) are data on the same API.
    fields: [],
    columns: ['specCode', 'productOrderId', 'state', 'message', 'startedAt'],
    detail: async (item) => {
      const res = await authFetch(`${PROCESS_BASE}/processFlow/${item.id}`);
      const flow = res.ok ? await res.json() : { taskFlow: [], timeline: [] };
      return (flow.taskFlow || []).map((t) => ({
        task: `${t.code} — ${t.name}`,
        state: t.state,
        owes: t.allowanceSeconds ? `${t.allowanceSeconds}s` : '—',
        note: t.message || '',
      })).concat((flow.timeline || []).map((e) => ({
        task: `⏱ ${e.eventType}`,
        state: e.sourceTopic,
        owes: String(e.eventTime).slice(11, 19),
        note: (e.digest || '').slice(0, 80),
      })));
    },
  },
  {
    path: 'runbook',
    base: '/ai/v1',
    title: 'Runbooks',
    readOnly: true,
    // procedural memory: promoted from N human-confirmed traces; approval
    // is the gate, revocation the brake — versioned, provenance attached
    fields: [],
    columns: ['signature', 'version', 'status', 'diagnosis', 'decidedNote'],
    rowAction: {
      // deciding is ai:admin (governance) — others see the library read-only
      label: (item) => {
        const roles = (tokenClaims().realm_access || {}).roles || [];
        if (!roles.includes('ai:admin')) return '';
        return item.status === 'proposed' ? 'Decide'
          : item.status === 'approved' ? 'Revoke' : '';
      },
      apply: async (item) => {
        if (item.status === 'proposed') {
          const d = window.prompt('Decision — "approve" or "reject":');
          if (!d || !['approve', 'reject'].includes(d)) return;
          const note = window.prompt('Decision note (kept on the runbook):') || '';
          await authFetch(`/ai/v1/runbook/${item.id}/${d}`, {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ note }),
          });
        } else if (item.status === 'approved') {
          const note = window.prompt('Revoke this runbook — why?');
          if (note === null) return;
          await authFetch(`/ai/v1/runbook/${item.id}/revoke`, {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ note }),
          });
        }
      },
    },
  },
  {
    path: 'serviceableArea',
    base: QUALIFICATION_BASE,
    title: 'Serviceable Areas',
    noEdit: true,
    fields: [
      { name: 'name', label: 'Name' },
      { name: 'productOffering', label: 'Offering', kind: 'ref', resource: 'productOffering', referredType: 'ProductOffering' },
      { name: 'postcodePrefix', label: 'Postcode prefix', required: true },
    ],
    columns: ['name', 'productOffering', 'postcodePrefix', 'lastUpdate'],
  },
  {
    // TMF645 coverage-as-data: what the NETWORK delivers per postcode prefix —
    // the serviceability answer's source of truth (empty prefix = everywhere).
    path: 'coverageMap',
    base: SQM_BASE,
    title: 'Coverage',
    noEdit: true,
    fields: [
      { name: 'technology', label: 'Technology (fiber / vdsl / 5g-fwa)', required: true },
      { name: 'postcodePrefix', label: 'Postcode prefix (empty = everywhere)' },
      { name: 'maxDownMbps', label: 'Max down Mbit/s', kind: 'number' },
      { name: 'maxUpMbps', label: 'Max up Mbit/s', kind: 'number' },
      // Wholesale footprint: paint WHICH owner sells access here and at WHICH layer.
      // Leave both blank for the operator's OWN network (a normal serviceability row).
      { name: 'accessOwner', label: 'Access owner code (blank = own network)' },
      { name: 'accessLayer', label: 'Access layer (blank = own network)', kind: 'select',
        options: [{ label: 'L2-VULA', value: 'L2-VULA' }, { label: 'L3-activated', value: 'L3-activated' }] },
      { name: 'note', label: 'Note' },
    ],
    columns: ['technology', 'postcodePrefix', 'maxDownMbps', 'maxUpMbps', 'accessOwner', 'accessLayer', 'note', 'lastUpdate'],
  },
  {
    // TMF633 Service Catalog: the CFS/RFS the wholesale offering is realised by.
    // A CFS (customer-facing access) reliesOn an RFS (the owner's bitstream).
    path: 'serviceSpecification',
    base: SERVICE_CATALOG_BASE,
    title: 'Service specs (CFS/RFS)',
    fields: [
      { name: 'name', label: 'Name', required: true },
      { name: 'serviceType', label: 'Kind', kind: 'select',
        options: [{ label: 'CFS — customer-facing', value: 'CFS' }, { label: 'RFS — resource-facing', value: 'RFS' }] },
      { name: 'version', label: 'Version' },
      { name: 'description', label: 'Description', kind: 'longtext' },
      { name: 'serviceSpecCharacteristic', label: 'Characteristics — JSON array (accessLayer, downloadSpeed, …)', kind: 'jsontext' },
      { name: 'serviceSpecRelationship', label: 'Relationships — JSON array (a CFS reliesOn its RFS)', kind: 'jsontext' },
    ],
    columns: ['name', 'serviceType', 'version', 'lifecycleStatus', 'lastUpdate'],
  },
  { path: 'wholesaleOwners', title: 'Access owners', wholesaleOwners: true },
  { path: 'accessProduct', title: 'Access products', accessProduct: true },
  { path: 'wholesaleSettlement', title: 'Settlement', wholesaleSettlement: true },
  { path: 'mobileWholesale', title: 'Mobile wholesale', mobileWholesale: true },
  { path: 'mobileWholesaleProvider', title: 'Mobile — host (provider)', mobileWholesaleProvider: true },
  {
    // TMF696 risk assessments — read-only: the scores the ordering gate consults.
    path: 'partyRiskAssessment',
    base: RISK_BASE,
    title: 'Risk',
    readOnly: true,
    fields: [],
    columns: ['relatedParty', 'risk', 'status'],
    augmentRow: async (item, cell) => {
      const r = item.riskAssessmentResult || {};
      const top = ((r.signal || [])[0] || {}).label || '';
      cell.textContent = r.overallScore != null
        ? `${r.overallScore} · ${r.riskLevel}${top ? ' — ' + top : ''}` : '—';
    },
  },
  {
    // The back office's raw ORDER LEDGER (demo-found gap: "is the mobile item
    // done?"). Read-only: state changes belong to the order lifecycle, not a
    // console edit; Process flows stays the per-phase ops view.
    path: 'productOrder',
    base: ORDERING_BASE,
    title: 'Orders',
    readOnly: true,
    fields: [],
    columns: ['state', 'orderDate', 'customer', 'progress', 'id'],
    augmentRow: async (item, cell, col) => {
      if (col === 'customer') {
        const p = (item.relatedParty || []).find((r) => r.role === 'customer')
          || (item.relatedParty || [])[0];
        cell.textContent = p ? await partyName(p.id) : '—';
        return;
      }
      // 'progress' — per-leaf item state, so an agent sees WHAT is pending and
      // what is done (a physical device stays inProgress until it is delivered;
      // an eSIM completes in seconds). Pending items lead, done items follow.
      const leaves = [];
      const walk = (arr) => (arr || []).forEach((i) => {
        if ((i.productOrderItem || []).length) walk(i.productOrderItem);
        else leaves.push({ name: (i.productOffering || {}).name || 'item', state: i.state || '?' });
      });
      walk(item.productOrderItem);
      const glyph = (st) => st === 'completed' ? '✓' : st === 'cancelled' ? '✗' : '⏳';
      leaves.sort((a, b) => (a.state === 'completed' ? 1 : 0) - (b.state === 'completed' ? 1 : 0));
      cell.textContent = leaves.map((l) => `${glyph(l.state)} ${l.name}: ${l.state}`).join('  ·  ') || '—';
      cell.title = cell.textContent;
    },
    // The JOURNEY, expanded in place — WHY the order is where it is, so an agent
    // reads the whole story on the Orders row instead of phoning provisioning.
    detail: async (item) => {
      const g = (st) => st === 'completed' ? '✓' : st === 'cancelled' ? '✗'
        : (st === 'failed' || st === 'held') ? '⚠' : '⏳';
      // "failed" from the tracking layer = OVERDUE, not hard-failed — say so plainly.
      const label = (st) => st === 'failed' ? 'overdue' : st;
      const list = await (await authFetch(`${PROCESS_BASE}/processFlow?productOrderId=${item.id}`)).json();
      if (!list.length) return [{ Step: '—', Status: 'no journey recorded', Detail: 'this order predates the timeline projection' }];
      const flow = await (await authFetch(`${PROCESS_BASE}/processFlow/${list[0].id}`)).json();
      const sum = flow.summary || {};
      const rows = [{ Step: '▶ ' + (sum.headline || flow.state), Status: sum.needsAttention ? 'needs a nudge' : '', Detail: sum.why || '' }];
      if (sum.needsAttention) {
        rows.push({ Step: 'ⓘ what "overdue" means', Status: '',
          Detail: 'a milestone did not arrive within its time window — the order may need a nudge; it does NOT mean the order failed (the service may already be active).' });
      }
      for (const t of (flow.taskFlow || [])) {
        rows.push({ Step: `${g(t.state)} ${t.name}`, Status: label(t.state), Detail: t.message || '—' });
      }
      // the raw event journal underneath, for the agent who wants the receipts
      for (const e of (flow.timeline || [])) {
        rows.push({ Step: '⏱ ' + e.eventType, Status: String(e.eventTime || '').slice(0, 19).replace('T', ' '),
          Detail: (e.sourceTopic || '') });
      }
      return rows;
    },
  },
  {
    path: 'appointment',
    base: APPOINTMENT_BASE,
    title: 'Appointments',
    readOnly: true,
    fields: [],
    columns: ['description', 'validFor', 'status', 'relatedParty', 'lastUpdate'],
  },
);
