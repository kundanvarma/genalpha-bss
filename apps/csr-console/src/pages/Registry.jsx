import { useState } from 'react';
import { directoryExportRun, runDirectoryExport, runRegistrySync } from '../api.js';

/**
 * Back-office registry desk: the manual registry-sync poke (the scheduled
 * worker does the same on a clock) and the number-directory delta export —
 * run it, see exactly which rows left the building. Protected-address and
 * secret-number suppression happens server-side; what is listed here is
 * what the directory partner receives.
 */
const dt = (v) => (v ? new Date(v).toLocaleString(undefined,
  { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—');

export default function Registry() {
  const [syncResult, setSyncResult] = useState(null);
  const [run, setRun] = useState(null);
  const [runId, setRunId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  async function act(fn) {
    setBusy(true);
    setError(null);
    try { await fn(); } catch (e) { setError(e.message); }
    setBusy(false);
  }

  return (
    <>
      <h1>Registry &amp; directory</h1>
      {error && <p className="error" data-testid="registry-error">{error}</p>}

      <h2>National registry sync</h2>
      <p className="dim small">
        Polls the registry event feed now (address changes, protections, deaths)
        and applies what it finds — the scheduled worker does this on a clock.
      </p>
      <div className="stack">
        <button className="ghost" disabled={busy} data-testid="registry-sync-run"
                onClick={() => act(async () => setSyncResult(await runRegistrySync()))}>
          Run sync now
        </button>
        {syncResult && (
          <span className="dim small" data-testid="registry-sync-result" style={{ alignSelf: 'center' }}>
            processed {syncResult.processed ?? 0} event{(syncResult.processed ?? 0) === 1 ? '' : 's'}
            {syncResult.lastSeq != null ? ` · cursor at ${syncResult.lastSeq}` : ''}
          </span>
        )}
      </div>

      <h2>Directory export</h2>
      <p className="dim small">
        The delta file the number-directory agreement requires. Reserved
        exposure, secret numbers and protected addresses never leave.
      </p>
      <div className="stack">
        <button className="ghost" disabled={busy} data-testid="directory-export-run"
                onClick={() => act(async () => {
                  const r = await runDirectoryExport();
                  setRun(r);
                  setRunId(r.id || '');
                })}>
          Run export now
        </button>
        <input placeholder="…or look up a past run by id" data-testid="directory-run-id"
               value={runId} onChange={(e) => setRunId(e.target.value)} />
        <button className="ghost" disabled={busy || !runId.trim()} data-testid="directory-run-view"
                onClick={() => act(async () => setRun(await directoryExportRun(runId.trim())))}>
          View run
        </button>
      </div>

      {run && (
        <section data-testid="directory-run">
          <h2>Run {String(run.id || '').slice(0, 8)}…
            <span className="secnone"> — {run.rowCount ?? (run.rows || []).length} row{(run.rowCount ?? (run.rows || []).length) === 1 ? '' : 's'} · {dt(run.ranAt)}</span>
          </h2>
          <div className="rows" data-testid="directory-run-rows">
            {(run.rows || []).map((p, i) => (
              <div className="row" key={`${p.partyId || ''}-${i}`} data-testid="directory-export-row">
                <div>
                  <strong>{p.name || p.partyId}</strong>
                  <div className="dim small">
                    {p.exposure || '—'}
                    {p.phoneNumber ? ` · ${p.phoneNumber}` : ''}
                    {p.address ? ` · ${[p.address.street1, p.address.postCode, p.address.city].filter(Boolean).join(', ')}` : ''}
                  </div>
                </div>
                <span className="dim small">{p.serviceRef || ''}</span>
              </div>
            ))}
            {!(run.rows || []).length && <p className="dim small">This run exported no rows.</p>}
          </div>
        </section>
      )}
    </>
  );
}
