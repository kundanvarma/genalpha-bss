import { hasRole } from '../../auth.js';
import { openBillPdf, resendBill, issueCreditNote, disputeBill, splitBill, setBillDeliveryFor, patchSpendPolicy,
  revokePaymentMethod, logInteraction } from '../../api.js';

/* Billing & account: state first, configuration when asked for. The Overview
 * shows one line per topic (bills current / overdue, roaming 0 of 50, content
 * on) and this area holds the controls. Nothing here is hidden when it warns. */

const party = (id) => [{ id, role: 'customer', '@referredType': 'Individual' }];
const None = () => <span className="secnone"> — none</span>;
const dt = (v) => v ? new Date(v).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—';

export { OPEN_BILL_STATES, due, situationOf, SITUATION_WORDS, STILL_OWING, stillOwing } from './situation.jsx';
import { situationOf, SITUATION_WORDS } from './situation.jsx';

export { accountState } from './accountState.jsx';

export function Bills({ bills, id, act }) {
  return (
    <>
      <h2>Bills{!bills.length && <None />}
        <select className="ghost" data-testid="csr-bill-delivery" defaultValue="" aria-label="Bill delivery"
          title="How this customer's bill is delivered — their choice overrides the operator default"
          style={{ marginLeft: 10, fontSize: 13 }}
          onChange={(e) => {
            const v = e.target.value;
            if (!v) return;
            act(async () => {
              await setBillDeliveryFor(id, v === 'default' ? null : v);
              await logInteraction({ description: `Bill delivery preference set to ${v} on request`, channel: 'phone', direction: 'inbound', sourceSystem: 'csr-console', relatedParty: party(id) });
            }, 'billing');
          }}>
          <option value="" disabled>Bill delivery…</option>
          <option value="digital">Digital only</option>
          <option value="einvoice">E-invoice</option>
          <option value="paper">Paper by post</option>
          <option value="default">Operator default</option>
        </select>
      </h2>
      <div className="rows" data-testid="bills-card">
        {bills.map((b) => (
          <div className="row" key={b.id}>
            <span>{b.billNo}{b.dispute && b.dispute.status === 'open' && <span className="state cancelled" style={{ marginLeft: 8 }}>disputed</span>}</span>
            <span className="rowend">
              <span className="linetotal">{b.amountDue.value.toFixed(2)} {b.amountDue.unit}</span>
              {b.installmentPlan && b.installmentPlan.status !== 'cancelled' && (
                <span className="dim small">{b.installmentPlan.paidCount}/{b.installmentPlan.installments} paid</span>
              )}
              <span className={`state ${situationOf(b) || b.state}`}
                    data-testid="csr-bill-situation"
                    title={(b.billSituation || {}).reason || ''}>
                {SITUATION_WORDS[situationOf(b)] || b.state}
              </span>
              <button className="ghost" data-testid="csr-bill-pdf" title="Open the bill exactly as the customer sees it" onClick={() => act(() => openBillPdf(b.id), 'billing')}>PDF</button>
              <details className="more">
                <summary className="ghost">More…</summary>
                <span className="more-actions">
                  <button className="ghost" data-testid="csr-resend-bill" title="Email a copy of this invoice (PDF) to the customer's address on file"
                      onClick={() => act(async () => {
                        await resendBill(b.id);
                        await logInteraction({ description: `Invoice copy of ${b.billNo} emailed to the customer's address on file`, channel: 'phone', direction: 'outbound', sourceSystem: 'csr-console', relatedParty: party(id) });
                      }, 'billing')}>Email bill</button>
                  <button className="ghost" data-testid="csr-issue-credit-note" title="Issue a numbered credit note — reduces an unpaid bill or refunds a settled one (billing:admin)"
                      onClick={() => {
                        const amount = window.prompt('Credit amount (blank = the full remaining amount):');
                        if (amount === null) return;
                        const reason = window.prompt('Reason (required — the credit note carries it):');
                        if (!reason) return;
                        act(async () => {
                          const cn = await issueCreditNote(b.id, amount ? Number(amount) : null, reason);
                          await logInteraction({ description: `Credit note ${cn.creditNoteNo} issued on ${b.billNo}: ${cn.amount.value} ${cn.amount.unit} — ${reason}`, channel: 'phone', direction: 'outbound', sourceSystem: 'csr-console', relatedParty: party(id) });
                        }, 'billing');
                      }}>Credit note</button>
                  {(!b.dispute || b.dispute.status !== 'open') && (
                    <button className="ghost" data-testid="csr-dispute-bill" title="Open a dispute for the caller — collection pauses while it is investigated"
                        onClick={() => {
                          const reason = window.prompt('What does the caller say is wrong?');
                          if (!reason) return;
                          act(async () => {
                            await disputeBill(b.id, reason);
                            await logInteraction({ description: `Dispute opened on ${b.billNo}: ${reason}`, channel: 'phone', direction: 'inbound', sourceSystem: 'csr-console', relatedParty: party(id) });
                          }, 'billing');
                        }}>Dispute</button>
                  )}
                  {b.state === 'new' && !b.installmentPlan && (
                    <button className="ghost" data-testid="csr-split-bill" title="Split into monthly installments — the customer pays each part in the shop"
                        onClick={() => {
                          const n = window.prompt('Split into how many monthly payments? (2-12)', '3');
                          if (!n) return;
                          act(async () => {
                            await splitBill(b.id, Number(n));
                            await logInteraction({ description: `Bill ${b.billNo} (${b.amountDue.value.toFixed(2)} ${b.amountDue.unit}) split into ${n} installments on request`, channel: 'phone', direction: 'outbound', sourceSystem: 'csr-console', relatedParty: party(id) });
                          }, 'billing');
                        }}>Installments</button>
                  )}
                </span>
              </details>
            </span>
          </div>
        ))}
      </div>
    </>
  );
}

export function Usage({ usage }) {
  return (
    <>
      <h2>Usage this month{!usage.length && <None />}</h2>
      <div className="rows" data-testid="usage-card">
        {usage.map((b, i) => (
          <div className="row" key={i}>
            <span>{b.name}</span>
            <span className={b.allowedValue != null && Number(b.usedValue) > Number(b.allowedValue) ? 'error' : 'dim'}>
              {b.usedValue}{b.allowedValue != null ? ` / ${b.allowedValue}` : ''} {b.units}
            </span>
          </div>
        ))}
      </div>
    </>
  );
}

export function Agreements({ agreements }) {
  return (
    <>
      <h2>Agreements{!agreements.length && <None />}</h2>
      <div className="rows" data-testid="agreements-card">
        {agreements.map((g) => (
          <div className="row" key={g.id}>
            <span>{g.name}{g.agreementPeriod?.endDateTime && <span className="dim small"> — until {g.agreementPeriod.endDateTime.slice(0, 10)}</span>}</span>
            <span className={`state ${g.status}`}>{g.status}</span>
          </div>
        ))}
      </div>
    </>
  );
}

export function PromoAndPayment({ redemptions, methods, act }) {
  return (
    <>
      <h2>Promotions &amp; payment{!redemptions.length && !methods.length && <None />}</h2>
      <div className="rows" data-testid="promo-vault-card">
        {redemptions.map((r) => (
          <div className="row" key={r.id}><span>Promo <strong>{r.code}</strong> — {r.name}</span><span className="dim">−{r.percentage}%</span></div>
        ))}
        {methods.map((m) => (
          <div className="row" key={m.id}>
            <span>{m.details.brand} •••• {m.details.lastFourDigits}<span className="dim small"> exp {m.details.expiry}</span></span>
            <button className="ghost danger" onClick={() => act(() => revokePaymentMethod(m.id), 'billing')}>Revoke</button>
          </div>
        ))}
        {!redemptions.length && !methods.length && <p className="dim small">No promotions or saved cards.</p>}
      </div>
    </>
  );
}

export function Policies({ spendPolicies, id, act }) {
  return (
    <>
      <h2>Spend &amp; roaming policies{!spendPolicies.length && <None />}</h2>
      <div className="rows" data-testid="policy-card">
        {spendPolicies.map((m) => (
          <div className="row" key={m.meterType} data-testid={`policy-${m.meterType}`}>
            <div>
              <strong>{{ spend: 'Spend cap', content: 'Content services', roaming: 'Roaming limit' }[m.meterType] || m.meterType}</strong>
              <div className="dim small">
                {m.limit ? `limit ${m.limit.value} ${m.limit.unit}` : 'no limit set'}
                {m.accrued ? ` · used ${m.accrued.value} ${m.accrued.unit}` : ''}
                {m.notifyAtPct != null ? ` · warns at ${m.notifyAtPct}%` : ''}
                {m.blockOnBreach ? ' · blocks on breach' : ''}
              </div>
            </div>
            <div className="rowend">
              {m.barred === true && <span className="state cancelled">barred</span>}
              {m.blocked === true && <span className="state cancelled">blocked</span>}
              {m.continueElected === true && <span className="state active">continue elected</span>}
              <span className={`state ${m.enabled ? 'active' : ''}`}>{m.enabled ? 'on' : 'off'}</span>
              {hasRole('usage:read') && ['roaming', 'content', 'spend'].includes(m.meterType) && (
                <button className="ghost" data-testid={`policy-limit-${m.meterType}`} title="Adjust the limit with the customer's say-so on the line (statutory floors apply)"
                    onClick={() => {
                      const v = window.prompt(`New ${m.meterType} limit (${m.limit?.unit || 'per cycle'}):`, m.limit ? String(m.limit.value) : '');
                      if (!v) return;
                      act(async () => {
                        await patchSpendPolicy(id, m.meterType, { limit: Number(v), enabled: true });
                        await logInteraction({ description: `${m.meterType} limit set to ${v} on request`, channel: 'phone', direction: 'inbound', sourceSystem: 'csr-console', relatedParty: party(id) });
                      }, 'billing');
                    }}>Set limit</button>
              )}
              {hasRole('usage:read') && m.meterType === 'content' && (
                <button className="ghost" data-testid="policy-bar-toggle" title="Content-service barring is free and always available; a minor's barring only a guardian or staff lifts"
                    onClick={() => act(async () => {
                      await patchSpendPolicy(id, 'content', { barred: !m.barred });
                      await logInteraction({ description: `Content-services barring ${m.barred ? 'lifted' : 'applied'} on request`, channel: 'phone', direction: 'inbound', sourceSystem: 'csr-console', relatedParty: party(id) });
                    }, 'billing')}>{m.barred ? 'Lift barring' : 'Bar content'}</button>
              )}
            </div>
          </div>
        ))}
      </div>
    </>
  );
}

export function Pool({ pools, id }) {
  return (
    <>
      <h2>Household pool{!pools.length && <None />}</h2>
      <div className="rows" data-testid="pool-card">
        {pools.map((p) => {
          const mine = (p.member || []).find((mb) => mb.partyId === id);
          return (
            <div className="row" key={p.id} data-testid="pool-row">
              <div>
                <strong>{p.name}</strong>
                <div className="dim small">
                  {p.ownerPartyId === id ? 'owner' : 'member'}
                  {p.remainingGB != null ? ` · ${p.remainingGB} of ${p.poolGB} ${p.units || 'GB'} left` : ''}
                  {mine && mine.consumedGB != null ? ` · this customer used ${mine.consumedGB}` : ''}
                  {mine && mine.softLimitGB != null ? ` · soft cap ${mine.softLimitGB}` : ''}
                  {mine && mine.hardLimitGB != null ? ` · hard cap ${mine.hardLimitGB}` : ''}
                </div>
              </div>
              <span className={`state ${p.status}`}>{p.status}</span>
            </div>
          );
        })}
      </div>
    </>
  );
}

export function AutoTopup({ autoTopup }) {
  return (
    <>
      <h2>Auto top-up{(!autoTopup || !autoTopup.enabled) && <None />}</h2>
      <div className="rows" data-testid="autotopup-card">
        {autoTopup && autoTopup.enabled ? (
          <div className="row">
            <div>
              <strong>Auto top-up on</strong>
              <div className="dim small">
                boost {autoTopup.boostOfferingId || '—'} · trigger {autoTopup.trigger || 'depletion'}
                {autoTopup.triggerPct != null ? ` at ${autoTopup.triggerPct}%` : ''}
                {autoTopup.maxBoostsPerCycle != null ? ` · max ${autoTopup.maxBoostsPerCycle}/cycle` : ''}
                {autoTopup.consentAt ? ` · consented ${String(autoTopup.consentAt).slice(0, 10)}` : ''}
              </div>
            </div>
            <span className="state active">enabled</span>
          </div>
        ) : <p className="dim small">Off — it only ever turns on with the customer's recorded consent.</p>}
      </div>
    </>
  );
}

export function CreditDecisions({ creditDecisions }) {
  return (
    <>
      <h2>Credit decisions{!creditDecisions.length && <None />}</h2>
      <div className="rows" data-testid="credit-card">
        {creditDecisions.map((cd) => (
          <div className="row" key={cd.id} data-testid="credit-decision-row">
            <div>
              <strong className={cd.decision === 'approve' ? 'ok' : cd.decision === 'decline' ? 'error' : undefined}>{cd.decision}</strong>
              <div className="dim small">
                {cd.scoreBand ? `band ${cd.scoreBand}` : 'no score band'}
                {cd.purpose ? ` · ${cd.purpose}` : ''}
                {cd.remarksPresent === true ? ' · payment remarks on file' : ''}
              </div>
            </div>
            <span className="dim small">{dt(cd.decidedAt)}</span>
          </div>
        ))}
        {!creditDecisions.length && <p className="dim small">No stored decisions — only the decision is ever kept, never a report.</p>}
      </div>
    </>
  );
}
