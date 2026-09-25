/* Field kinds → form controls (text, checkbox, JSON, multiselect, select, recipe, long text, code pick, money, quantity, commitment). */
'use strict';

function textControl(field, type) {
  const input = document.createElement('input');
  input.type = type;
  if (type === 'number') input.step = 'any';
  input.name = field.name;
  input.placeholder = field.placeholder || '';
  input.required = Boolean(field.required);
  controls[field.name] = {
    get: () => {
      const v = input.value.trim();
      if (!v) return undefined;
      return type === 'number' ? Number(v) : v;
    },
    set: (item) => { input.value = item[field.name] ?? ''; },
  };
  return [input];
}

/* A calendar, not a typed timestamp. The wire wants an instant (TMF `validFor`
 * is a date-time); a product manager means a day. So: a native date picker,
 * read back as the local day, written as that day's first moment — or its last
 * when the field is the closing end of a window (`endOfDay: true`), because
 * "available until 31 October" means the whole of the 31st. A field marked
 * `plain: true` keeps the bare YYYY-MM-DD the API asked for. */
function dateControl(field) {
  const input = document.createElement('input');
  input.type = 'date';
  input.name = field.name;
  input.required = Boolean(field.required);
  const iso = (day, endOfDay) => {
    const [y, m, d] = day.split('-').map(Number);
    const at = endOfDay ? new Date(y, m - 1, d, 23, 59, 59, 999) : new Date(y, m - 1, d, 0, 0, 0, 0);
    return at.toISOString();
  };
  const dayOf = (value) => {
    if (!value) return '';
    const at = new Date(value);
    if (Number.isNaN(at.getTime())) return String(value).slice(0, 10);
    const pad = (n) => String(n).padStart(2, '0');
    return `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}`;
  };
  controls[field.name] = {
    get: () => {
      const day = input.value.trim();
      if (!day) return undefined;
      return field.plain ? day : iso(day, Boolean(field.endOfDay));
    },
    // a field may live under another name on the wire (the offering's window is
    // `validFor.startDateTime`, not `validFrom`) — `read` says where to look;
    // without it the form opened blank, which it did for as long as it existed
    set: (item) => { input.value = dayOf(field.read ? field.read(item) : item[field.name]); },
  };
  return [input];
}

function checkboxControl(field) {
  const input = document.createElement('input');
  input.type = 'checkbox';
  input.name = field.name;
  controls[field.name] = {
    get: () => input.checked,
    set: (item) => { input.checked = Boolean(item[field.name]); },
  };
  return [input];
}

function jsonTextControl(field) {
  const input = document.createElement('textarea');
  input.name = field.name;
  input.placeholder = field.placeholder || '';
  input.rows = 3;
  controls[field.name] = {
    get: () => {
      const v = input.value.trim();
      if (!v) return undefined;
      try {
        return JSON.parse(v);
      } catch {
        throw new Error(`${field.label}: not valid JSON`);
      }
    },
    set: (item) => {
      input.value = item[field.name] ? JSON.stringify(item[field.name], null, 1) : '';
    },
  };
  return [input];
}

/** A checkbox group over a fixed vocabulary — yields [{id, name, @referredType}] or nothing. */
function multiselectControl(field) {
  const wrap = document.createElement('div');
  wrap.className = 'multiselect';
  wrap.style.cssText = 'display:flex;flex-wrap:wrap;gap:.35rem .9rem';
  const boxes = [];
  for (const opt of field.options) {
    const l = document.createElement('label'); l.style.cssText = 'display:inline-flex;gap:.3rem;align-items:center;font-weight:400';
    const cb = document.createElement('input'); cb.type = 'checkbox'; cb.value = opt.value; cb.name = field.name + '.' + opt.value;
    l.append(cb, document.createTextNode(opt.label)); wrap.append(l); boxes.push({ cb, opt });
  }
  controls[field.name] = {
    get: () => {
      const picked = boxes.filter((b) => b.cb.checked).map((b) => ({ id: b.opt.value, name: b.opt.label, '@referredType': field.refType || 'Ref' }));
      return picked.length ? picked : (editingId ? [] : undefined);
    },
    set: (item) => { const ids = new Set((item[field.name] || []).map((c) => String(c.id || c))); boxes.forEach((b) => { b.cb.checked = ids.has(b.opt.value); }); },
  };
  return [wrap];
}

function selectControl(field) {
  const select = document.createElement('select');
  select.name = field.name;
  select.required = Boolean(field.required);
  select.append(new Option('—', ''));
  for (const opt of field.options) {
    select.append(new Option(opt.label, opt.value));
  }
  if (field.default) select.value = field.default;
  controls[field.name] = {
    get: () => select.value || undefined,
    set: (item) => { select.value = item[field.name] ?? (item.id ? '' : field.default ?? ''); },
  };
  return [select];
}

/** A select that PREFILLS sibling fields (option.fill) — never sent itself. */
function recipeControl(field) {
  const select = document.createElement('select');
  select.name = field.name;
  select.append(new Option('— from scratch —', ''));
  for (const opt of field.options) {
    select.append(new Option(opt.label, opt.value));
  }
  select.addEventListener('change', () => {
    const opt = field.options.find((o) => o.value === select.value);
    if (!opt?.fill) return;
    for (const [name, value] of Object.entries(opt.fill)) {
      controls[name]?.set({ [name]: value });
    }
  });
  controls[field.name] = {
    get: () => undefined, // a starting point, not a payload field
    set: () => { select.value = ''; },
  };
  return [select];
}

function longTextControl(field) {
  const input = document.createElement('textarea');
  input.name = field.name;
  input.placeholder = field.placeholder || '';
  input.required = Boolean(field.required);
  input.rows = 3;
  input.style.font = 'inherit';
  controls[field.name] = {
    get: () => input.value.trim() || undefined,
    set: (item) => { input.value = item[field.name] ?? ''; },
  };
  return [input];
}


/** Picklist over an API resource whose chosen value is one plain attribute. */
function codePickControl(field) {
  const select = document.createElement('select');
  select.name = field.name;
  select.append(new Option('—', ''));
  loadPicklist(field).then((items) => {
    for (const item of items) {
      const code = item[field.attribute];
      if (code) select.append(new Option(`${code} — ${item.name || ''}`.trim(), code));
    }
  });
  controls[field.name] = {
    get: () => select.value || undefined,
    set: (item) => { select.value = item[field.name] ?? ''; },
  };
  return [select];
}

function moneyControl(field) {
  const amount = document.createElement('input');
  amount.type = 'number';
  amount.step = 'any';
  amount.placeholder = 'amount';
  const unit = document.createElement('input');
  const tenantCurrency = (window.BSS_CONSOLE_CONFIG || {}).currency || 'EUR';
  unit.placeholder = tenantCurrency;
  unit.value = tenantCurrency;
  unit.className = 'unit';
  const row = document.createElement('div');
  row.className = 'moneyrow';
  row.append(amount, unit);
  controls[field.name] = {
    get: () => {
      if (!amount.value.trim()) return undefined;
      return { unit: unit.value.trim() || tenantCurrency, value: Number(amount.value) };
    },
    set: (item) => {
      amount.value = item[field.name]?.value ?? '';
      unit.value = item[field.name]?.unit ?? tenantCurrency;
    },
  };
  return [row];
}

function quantityControl(field) {
  const amount = document.createElement('input');
  amount.type = 'number';
  amount.step = '1';
  amount.min = '0';
  amount.placeholder = 'amount';
  const units = document.createElement('input');
  units.placeholder = 'units (unit)';
  units.className = 'unit';
  const row = document.createElement('div');
  row.className = 'moneyrow';
  row.append(amount, units);
  controls[field.name] = {
    get: () => {
      if (!amount.value.trim()) return undefined;
      return { amount: Number(amount.value), units: units.value.trim() || 'unit' };
    },
    set: (item) => {
      amount.value = item[field.name]?.amount ?? '';
      units.value = item[field.name]?.units ?? '';
    },
  };
  return [row];
}


/** Commitment terms without JSON: none / 12 / 24 months. */
function commitmentControl(field) {
  const select = document.createElement('select');
  select.name = field.name;
  for (const [label, months] of [['No commitment', 0], ['12-month commitment', 12], ['24-month commitment', 24]]) {
    select.append(new Option(label, String(months)));
  }
  controls[field.name] = {
    get: () => {
      const months = Number(select.value);
      return months > 0 ? [{ name: `${months}-month commitment`,
        duration: { amount: months, units: 'month' } }] : undefined;
    },
    set: (item) => {
      const term = (item[field.name] || [])[0];
      select.value = String(term?.duration?.amount || 0);
    },
  };
  return [select];
}
