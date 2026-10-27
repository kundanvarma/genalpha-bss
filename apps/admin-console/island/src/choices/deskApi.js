/* The Choices desk's reads and writes (#359): the catalog, nothing else. A
 * benefit is an ordinary TMF620 offering with house fields beside it; it is
 * created through the same door as any offering, so launch governance decides
 * whether it goes on sale at once or waits for an approver. */
const CATALOG = '/tmf-api/productCatalogManagement/v4';

async function readJson(res, what) {
  if (!res.ok) {
    // an unreadable error body still says what happened: the status
    const problem = await res.json().catch(() => ({ message: `${what} answered ${res.status}` }));
    throw new Error(problem.message || `${what} answered ${res.status}`);
  }
  return res.json();
}

/** The whole shelf, page by page — a page is capped at 100 and the catalog is larger. */
export async function allOfferings(authFetch) {
  const found = [];
  let total = Infinity;
  for (let offset = 0; offset < total; offset += 100) {
    const res = await authFetch(`${CATALOG}/productOffering?limit=100&offset=${offset}`);
    const page = await readJson(res, 'the catalog');
    found.push(...page);
    const counted = Number(res.headers.get('X-Total-Count'));
    total = Number.isFinite(counted) && counted >= 0 ? counted : (page.length < 100 ? 0 : Infinity);
  }
  return found;
}

/** A category by name, made when it does not exist yet (the benefit's group). */
export async function categoryNamed(authFetch, name) {
  const all = await readJson(await authFetch(`${CATALOG}/category?limit=100`), 'the catalog');
  const found = all.find((c) => c.name === name);
  if (found) return found;
  return readJson(await authFetch(`${CATALOG}/category`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, lifecycleStatus: 'Active' }),
  }), 'the catalog');
}

export async function createOffering(authFetch, body) {
  return readJson(await authFetch(`${CATALOG}/productOffering`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  }), 'the catalog');
}

export async function patchOffering(authFetch, id, body) {
  return readJson(await authFetch(`${CATALOG}/productOffering/${encodeURIComponent(id)}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  }), 'the catalog');
}

const ref = (c) => ({ id: c.id, name: c.name, '@referredType': 'Category' });
export { ref as categoryRef };
