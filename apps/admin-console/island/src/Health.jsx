import { useEffect, useState } from 'react';

/* The first island, and the proof the seam works: it renders live data through
 * the shell's own authenticated fetch, so a React screen needs no second
 * sign-in and no second API client. Small on purpose — the real screens
 * (the Billing & Revenue overview, the offering workspace) follow this shape. */
export function Health({ authFetch, user }) {
  const [state, setState] = useState({ loading: true });
  useEffect(() => {
    let alive = true;
    authFetch('/tmf-api/productCatalogManagement/v4/productOffering?limit=1')
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`the catalog answered ${r.status}`))))
      .then((rows) => alive && setState({ loading: false, reachable: true, sample: rows[0]?.name }))
      .catch((e) => alive && setState({ loading: false, reachable: false, why: e.message }));
    return () => { alive = false; };
  }, [authFetch]);

  if (state.loading) return <p data-testid="island-health">Checking…</p>;
  return (
    <section data-testid="island-health">
      <h2 style={{ margin: '0 0 .4rem' }}>React island</h2>
      <p style={{ margin: 0 }}>
        {state.reachable
          ? `Signed in as ${user || 'this operator'}; the catalog answered${state.sample ? ` (first offering: ${state.sample})` : ''}.`
          : `The catalog could not be read: ${state.why}`}
      </p>
    </section>
  );
}
