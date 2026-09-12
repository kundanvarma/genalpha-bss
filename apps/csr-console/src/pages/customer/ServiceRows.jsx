import { useState } from 'react';
import { hasRole } from '../../auth.js';
import { simOf, resetSimPin, replaceSim, changeNumber, suspendService, resumeService, transferService,
  findCustomerByEmail, diagnoseService, ceaseService, logInteraction } from '../../api.js';

/* Capability-driven service rows. What a service IS decides which actions exist
 * on it: a PUK belongs to a SIM, a Wi-Fi check to a broadband line, a channel
 * package to TV. Not applicable → not shown. Applicable but not now (a paused
 * line) → disabled with the reason. Two direct actions at most; the rest behind
 * More…; the destructive one (Cease) lives in the danger zone of the Services
 * area with its consequences spelled out, never beside Diagnose. */

export function serviceKind(sv) {
  const t = String(sv.category || sv.serviceType || sv.serviceSpecification?.name || '').toLowerCase();
  const n = String(sv.name || '').toLowerCase();
  if (/mobile|sim|msisdn/.test(t) || /mobile|sim\b|5g|gb\b/.test(n)) return 'mobile';
  if (/broadband|fib|dsl|internet|access/.test(t) || /fiber|fibre|broadband|dsl|internet/.test(n)) return 'broadband';
  if (/\btv\b|iptv|stream/.test(t) || /\btv\b|stream/.test(n)) return 'tv';
  if (/voice|fixed/.test(t)) return 'voice';
  return 'other';
}
export const KIND_WORDS = { mobile: 'Mobile', broadband: 'Broadband', tv: 'TV', voice: 'Fixed voice', other: 'Service' };
export const numberOf = (sv) => (sv.supportingResource || []).map((r) => r.value).find(Boolean) || null;
export const placeOf = (sv) => {
  const p = (sv.place || [])[0];
  if (!p) return null;
  return p.name || [p.streetName || p.street1, p.postcode || p.postCode, p.city].filter(Boolean).join(', ') || null;
};
const charsOf = (sv) => Object.fromEntries((sv.serviceCharacteristic || []).map((c) => [c.name, c.value]));
const party = (id) => [{ id, role: 'customer', '@referredType': 'Individual' }];

/** The row's own facts: what the agent needs to tell services apart without opening anything. */
export function ServiceFacts({ sv, usage = [] }) {
  const kind = serviceKind(sv);
  const ch = charsOf(sv);
  const number = numberOf(sv);
  const place = placeOf(sv);
  const data = kind === 'mobile' ? usage.find((u) => /data|gb/i.test(u.name) && u.allowedValue != null) : null;
  return (
    <span className="svc-facts dim small">
      <span className="chip kind">{KIND_WORDS[kind]}</span>
      {number && <span className="msisdn">{number}</span>}
      {data && (
        <span className={Number(data.usedValue) > Number(data.allowedValue) ? 'error' : ''}>
          {Math.max(0, Number(data.allowedValue) - Number(data.usedValue))} {data.units} left of {data.allowedValue}
        </span>
      )}
      {kind !== 'mobile' && place && <span>{place}</span>}
      {ch.sliceProfile && ch.sliceProfile !== 'default' && (
        <span className="state slice" data-testid="csr-slice" title={`slice profile ${ch.sliceProfile}`} style={{ background: '#fde8d3', color: '#b45309', whiteSpace: 'nowrap' }}>
          ⚡ priority{ch.sliceUntil ? ` until ${new Date(ch.sliceUntil).toLocaleString(undefined, { weekday: 'short', hour: '2-digit', minute: '2-digit', ...((window.BSS_CSR_CONFIG || {}).timezone ? { timeZone: window.BSS_CSR_CONFIG.timezone } : {}) })}` : ''}
        </span>
      )}
      {sv.state === 'suspended' && <span className="error">paused</span>}
    </span>
  );
}

/** The actions a service can take right now — direct ones, and the rest behind More…. */
export function ServiceActions({ sv, id, act, puks, setPuks, onDiagnosis, compact = false, extra = null }) {
  const kind = serviceKind(sv);
  const number = numberOf(sv);
  const active = sv.state === 'active';
  const paused = sv.state === 'suspended';
  const why = paused ? 'the line is paused — resume it first' : null;

  const diagnose = (
    <button className="ghost" data-testid="csr-diagnose" key="diag"
        title={kind === 'broadband' ? 'Line, access and router — is it the network, the box or the plan?' : kind === 'mobile' ? '"It feels slow" — outage on their path? out of data? paused?' : 'Is it the network, the plan or the device?'}
        onClick={() => act(async () => {
          const report = await diagnoseService(sv.id);
          onDiagnosis?.({ ...report, serviceName: sv.name, kind });
          await logInteraction({
            description: `Line check on ${number || sv.name}: ` + report.findings.map((f) => f.code).join(', '),
            channel: 'phone', direction: 'inbound', sourceSystem: 'csr-console', relatedParty: party(id),
          });
        }, 'services')}>
      Diagnose
    </button>
  );
  const replaceSimBtn = kind === 'mobile' && (
    <button className="ghost" data-testid="csr-replace-sim" key="sim" disabled={!active} title={why || 'Block the old card at the network and issue a new one — the number stays; the customer is notified'}
        onClick={() => {
          const reason = window.prompt('Why is the SIM being replaced? (lost / stolen / damaged / upgrade)', 'lost');
          if (!reason) return;
          act(async () => {
            const done = await replaceSim(sv.id, reason.trim().toLowerCase());
            setPuks((p) => ({ ...p, [sv.id]: undefined }));
            await logInteraction({
              description: `SIM replaced (${reason.trim()}) for ${number} — old card blocked, new ${done.iccid} active`,
              channel: 'phone', direction: 'outbound', sourceSystem: 'csr-console', relatedParty: party(id),
            });
          }, 'services');
        }}>
      Replace SIM
    </button>
  );
  const resume = paused && (
    <button className="primary" data-testid="csr-resume-service" key="resume" onClick={() => act(() => resumeService(sv.id), 'services')}>Resume</button>
  );

  const more = [];
  if (kind === 'mobile') {
    more.push(puks[sv.id]
      ? <span className="dim small" data-testid="csr-puk" key="puk">PUK <strong>{puks[sv.id]}</strong></span>
      : <button className="ghost" data-testid="reveal-puk" key="puk" disabled={!active} title={why || 'Verify the caller\'s identity FIRST — the disclosure is logged'}
          onClick={() => act(async () => {
            const sim = await simOf(sv.id, true);
            if (!sim?.puk) throw new Error('No SIM on this service.');
            setPuks((p) => ({ ...p, [sv.id]: sim.puk }));
            await logInteraction({
              description: `PUK disclosed for ${number} after identity verification`,
              channel: 'phone', direction: 'outbound', sourceSystem: 'csr-console', relatedParty: party(id),
            });
          }, 'services')}>Reveal PUK</button>);
    more.push(<button className="ghost" data-testid="csr-change-number" key="num" disabled={!active} title={why || 'New number on the same line — the old one is quarantined; the customer is notified'}
        onClick={() => {
          if (!window.confirm(`Give ${number} a NEW number? The old one stops working immediately.`)) return;
          act(async () => {
            const done = await changeNumber(sv.id);
            await logInteraction({ description: `Number changed on request: ${done.oldNumber} → ${done.number}`, channel: 'phone', direction: 'outbound', sourceSystem: 'csr-console', relatedParty: party(id) });
          }, 'services');
        }}>Change number</button>);
    more.push(<button className="ghost" data-testid="csr-reset-pin" key="pin" disabled={!active} title={why || 'Push a new PIN to the card over the air — the customer is notified'}
        onClick={() => { const pin = window.prompt('New SIM PIN (4-8 digits) — agreed with the caller:'); if (pin) act(() => resetSimPin(sv.id, pin.trim()), 'services'); }}>Reset PIN</button>);
  }
  if (!paused) {
    more.push(<button className="ghost" data-testid="csr-pause-service" key="pause" disabled={!active} title={why || 'Vacation hold: charging pauses, number and SIM stay — lifts itself after the agreed days'}
        onClick={() => {
          const days = window.prompt('Pause this service for how many days? (1-90, blank = until they call back)', '30');
          if (days === null) return;
          act(async () => {
            await suspendService(sv.id, days.trim() ? Number(days.trim()) : undefined);
            await logInteraction({ description: `${number || sv.name} paused` + (days.trim() ? ` for ${days.trim()} days` : ' until further notice'), channel: 'phone', direction: 'outbound', sourceSystem: 'csr-console', relatedParty: party(id) });
          }, 'services');
        }}>Pause</button>);
  }
  more.push(<button className="ghost" data-testid="csr-transfer-service" key="xfer" disabled={!active} title={why || 'Move this service to another person — number, SIM and usage go with it'}
      onClick={() => {
        const q = window.prompt('Transfer this service to whom? (name or email)');
        if (!q) return;
        act(async () => {
          const hits = await findCustomerByEmail(q.trim());
          if (!hits.length) throw new Error(`No customer matches "${q}".`);
          const target = hits[0];
          if (!window.confirm(`Transfer ${number || sv.name} to ${target.givenName} ${target.familyName}?`)) return;
          await transferService(sv.id, target.id);
          await logInteraction({ description: `${number || sv.name} transferred to ${target.givenName} ${target.familyName}`, channel: 'phone', direction: 'outbound', sourceSystem: 'csr-console', relatedParty: party(id) });
        }, 'services');
      }}>Transfer</button>);

  // compact (Overview): two direct actions — Diagnose and the commercial one (upgrade); SIM work waits under More…
  if (compact && replaceSimBtn) more.unshift(replaceSimBtn);
  return (
    <div className="rowend svc-actions">
      <span className={`state ${sv.state}`}>{sv.state}</span>
      {resume}
      {diagnose}
      {compact ? extra : replaceSimBtn}
      {!compact && extra}
      {!compact || more.length ? (
        <details className="more" data-testid="csr-more-actions">
          <summary className="ghost">More…</summary>
          <span className="more-actions">{more}</span>
        </details>
      ) : null}
    </div>
  );
}

/** Ceasing, with the consequence in front of the agent before the click. */
export function DangerZone({ services, agreements = [], id, act }) {
  const [open, setOpen] = useState(false);
  const ceasable = services.filter((sv) => sv.state === 'active' && hasRole('service:write'));
  if (!ceasable.length) return null;
  return (
    <details className="danger-zone" data-testid="danger-zone" open={open} onToggle={(e) => setOpen(e.target.open)}>
      <summary>Danger zone — cease a service</summary>
      <p className="small">Ceasing disconnects the service at once, releases its number to quarantine, ends what depends on it and cannot be undone. Use Pause for a break, Transfer to move it to someone else.</p>
      {ceasable.map((sv) => {
        const number = numberOf(sv);
        const agreement = agreements.find((g) => g.status === 'active' && (g.name || '').toLowerCase().includes((sv.name || '').toLowerCase().split(' ')[0]));
        const consequences = [
          `${sv.name} stops now`,
          number ? `number ${number} is released (quarantined, not reusable at once)` : null,
          agreement ? `the agreement "${agreement.name}" ends — a residual may be billed` : null,
          (sv.serviceRelationship || []).length ? 'dependent services stop with it' : null,
        ].filter(Boolean);
        return (
          <div className="row" key={sv.id}>
            <span>{sv.name} {number && <span className="msisdn">{number}</span>}<span className="dim small" style={{ display: 'block' }}>{consequences.join(' · ')}</span></span>
            <button className="ghost danger" data-testid="cease-service"
                    onClick={() => window.confirm(`Cease ${sv.name}?\n\n• ${consequences.join('\n• ')}\n\nThis cannot be undone.`)
                      && act(() => ceaseService(sv.id, 'ceased by agent'), 'services')}>
              Cease
            </button>
          </div>
        );
      })}
    </details>
  );
}

/** The diagnosis, conclusion first. */
export function Diagnosis({ diagnosis }) {
  if (!diagnosis) return null;
  const cause = diagnosis.findings.find((f) => f.severity === 'cause');
  return (
    <div className="rows diagnosis" data-testid="csr-diagnosis">
      <p className={cause ? 'error' : 'ok'}>
        <strong>{diagnosis.serviceName ? `${diagnosis.serviceName}: ` : ''}{cause ? `likely issue — ${cause.message}` : 'nothing wrong found on this service'}</strong>
      </p>
      {diagnosis.findings.filter((f) => f !== cause).map((f, i) => <p key={i} className="dim small">{f.message}</p>)}
    </div>
  );
}
