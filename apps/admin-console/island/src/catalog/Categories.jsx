import { useCallback, useEffect, useState } from 'react';

const BASE = '/tmf-api/productCatalogManagement/v4/category';

/* THE SHELVES, AUTHORED (#155).
 *
 * Categories were always data — a first-class TMF620 entity with its own
 * endpoint — and there was no way to author one. The only route was editing
 * `ops/seed/seed_catalog_taxonomy.py` and rerunning it, which made demo
 * scaffolding load-bearing and meant "add a shelf" was a code change wearing a
 * different hat.
 *
 * Three things the page has to carry that a plain list would not:
 *  - a PARENT, so a flat list can become a two-level shelf. Nothing nests
 *    today; #143's nesting proposal cannot be adopted by an operator until a
 *    parent can be set here.
 *  - HOW MANY OFFERINGS sit on each shelf, so an empty one is visible on this
 *    page rather than discovered by a customer.
 *  - RETIRE rather than delete. Offerings point at a category by id; the API
 *    refuses to delete one still in use and says how many hold it.
 */
export function Categories({ authFetch }) {
  const [rows, setRows] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [draft, setDraft] = useState({ name: '', description: '', parentId: '' });
  const [editing, setEditing] = useState(null);

  const load = useCallback(() => {
    setError(null);
    return authFetch(`${BASE}?limit=100`)
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`the catalog answered ${r.status}`))))
      .then(setRows)
      .catch((e) => setError(e.message));
  }, [authFetch]);

  useEffect(() => { load(); }, [load]);

  const send = async (method, path, body) => {
    setBusy(true);
    setError(null);
    try {
      const res = await authFetch(`${BASE}${path}`, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: body ? JSON.stringify(body) : undefined,
      });
      if (!res.ok) {
        const problem = await res.json().catch(() => ({}));
        throw new Error(problem.message || `the catalog answered ${res.status}`);
      }
      await load();
      return true;
    } catch (e) {
      setError(e.message);
      return false;
    } finally {
      setBusy(false);
    }
  };

  const create = async (event) => {
    event.preventDefault();
    if (!draft.name.trim()) return;
    const made = await send('POST', '', {
      name: draft.name.trim(),
      description: draft.description.trim() || undefined,
      parentId: draft.parentId || undefined,
      lifecycleStatus: 'Active',
    });
    if (made) setDraft({ name: '', description: '', parentId: '' });
  };

  const saveEdit = async (event) => {
    event.preventDefault();
    const ok = await send('PATCH', `/${editing.id}`, {
      name: editing.name.trim(),
      description: editing.description || '',
      // "" is how this screen says "back to the top level"
      parentId: editing.parentId || '',
    });
    if (ok) setEditing(null);
  };

  const nameOf = (id) => (rows || []).find((c) => c.id === id)?.name;
  const active = (rows || []).filter((c) => c.lifecycleStatus !== 'Retired');

  if (rows === null) return <p data-testid="island-categories">Loading the shelves…</p>;

  return (
    <section data-testid="island-categories">
      <p className="dimhint" style={{ marginTop: 0 }}>
        Goal: every offering sits on a shelf a customer would recognise. Watch: shelves with nothing on them.
      </p>

      {error && <p className="error" data-testid="category-error">{error}</p>}

      <form onSubmit={create} data-testid="category-new"
        style={{ display: 'flex', gap: '.5rem', alignItems: 'flex-end', flexWrap: 'wrap', margin: '0 0 1rem' }}>
        <label style={{ display: 'grid', gap: '.2rem' }}>
          <span style={{ fontSize: '.8rem' }}>Shelf name *</span>
          <input name="name" required value={draft.name} placeholder="Equipment"
            onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
        </label>
        <label style={{ display: 'grid', gap: '.2rem' }}>
          <span style={{ fontSize: '.8rem' }}>What belongs here</span>
          <input name="description" value={draft.description} style={{ width: '18rem' }}
            placeholder="Routers, set-top boxes and accessories"
            onChange={(e) => setDraft({ ...draft, description: e.target.value })} />
        </label>
        <label style={{ display: 'grid', gap: '.2rem' }}>
          <span style={{ fontSize: '.8rem' }}>Sits under</span>
          <select name="parentId" value={draft.parentId}
            onChange={(e) => setDraft({ ...draft, parentId: e.target.value })}>
            <option value="">— top level —</option>
            {active.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </label>
        <button type="submit" className="primary" disabled={busy}>+ Add shelf</button>
      </form>

      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr>
            <th style={{ textAlign: 'left' }}>NAME</th>
            <th style={{ textAlign: 'left' }}>SITS UNDER</th>
            <th style={{ textAlign: 'left' }}>OFFERINGS</th>
            <th style={{ textAlign: 'left' }}>STATUS</th>
            <th />
          </tr>
        </thead>
        <tbody data-testid="category-rows">
          {rows.map((c) => (
            <tr key={c.id} data-category={c.name}>
              <td>{c.name}</td>
              <td>{nameOf(c.parentId) || '—'}</td>
              <td data-testid={`count-${c.id}`}>
                {c.offeringCount === 0
                  ? <span title="nothing is on this shelf">none yet</span>
                  : c.offeringCount}
              </td>
              <td>{c.lifecycleStatus || 'Active'}</td>
              <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                <button type="button" data-testid={`edit-${c.id}`} disabled={busy}
                  onClick={() => setEditing({ ...c, description: c.description || '', parentId: c.parentId || '' })}>
                  Rename
                </button>{' '}
                <button type="button" data-testid={`retire-${c.id}`} disabled={busy}
                  onClick={() => send('PATCH', `/${c.id}`,
                    { lifecycleStatus: c.lifecycleStatus === 'Retired' ? 'Active' : 'Retired' })}>
                  {c.lifecycleStatus === 'Retired' ? 'Bring back' : 'Retire'}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {editing && (
        <form onSubmit={saveEdit} data-testid="category-edit"
          style={{ display: 'flex', gap: '.5rem', alignItems: 'flex-end', flexWrap: 'wrap', marginTop: '1rem' }}>
          <label style={{ display: 'grid', gap: '.2rem' }}>
            <span style={{ fontSize: '.8rem' }}>Shelf name *</span>
            <input name="editName" required value={editing.name}
              onChange={(e) => setEditing({ ...editing, name: e.target.value })} />
          </label>
          <label style={{ display: 'grid', gap: '.2rem' }}>
            <span style={{ fontSize: '.8rem' }}>Sits under</span>
            <select name="editParent" value={editing.parentId}
              onChange={(e) => setEditing({ ...editing, parentId: e.target.value })}>
              <option value="">— top level —</option>
              {active.filter((c) => c.id !== editing.id)
                .map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </label>
          <button type="submit" className="primary" disabled={busy}>Save changes</button>
          <button type="button" onClick={() => setEditing(null)} disabled={busy}>Cancel</button>
        </form>
      )}
    </section>
  );
}
