# Telesales channel — outbound partners with dialers (plan)

Outsourced call centers sell subscriptions on the operator's behalf —
with their own dialer or the CSP's. Structurally a telesales partner IS
a dealer (agreement row, attribution, commission with clawback, machine
credential for their dialer — all built). What makes it a lawful
OUTBOUND channel is three new things:

1. **The DNC wash, fail-closed.** Norway's Reservasjonsregisteret: a
   number reserved against telemarketing may not be dialed/sold to. A
   per-tenant seam (`dnc-url`/`dnc-token`, mock-dnc container as the
   register) checked at OFFER time — and an unreachable register
   REFUSES, because "we couldn't check" is not consent.
2. **Confirm-after-call.** Under angrerettloven, a consumer telesales
   agreement is NOT BINDING until the consumer confirms in writing
   after the call. So the call produces a `telesales_offer` (SOM,
   beside the dealer domain): status offered, a confirmation token,
   an expiry (dev clock). NO order exists yet. The customer's inbox
   (and email via the ESP seam) carries the confirmation; POST
   /telesales/v1/confirm {token} — public, the token is the capability
   — creates the dealer-stamped order, activation follows, commission
   accrues from CONFIRMATION (the moment the agreement binds).
   Unconfirmed offers expire; expired tokens refuse.
3. **The dialer's calls land on the record.** Nothing new to build —
   the TMF683 open door was made for this: the dialer logs each call
   (channel phone, sourceSystem, disposition) and the CSR 360 reads it.

v1 targets the WARM BASE (existing customers — retention/upsell, the
most common CSP telesales); cold-prospect identity creation at confirm
is a named follow-up, as is the segment→dial-list export.

Proof (suite #51): a reserved number refuses; a DNC outage refuses
(fail-closed); a clean offer creates NO order until the customer
confirms from their own inbox token; confirmation orders + activates +
accrues pending commission to the call center with the campaign as the
store; an expired offer refuses; the dialer's call log shows on the
timeline.


## Shipped

Everything above, proven by suite #51 (telesales_test.js, eight checks).
Two design decisions sharpened during the build: confirmation is an
AUTHENTICATED act — the signed-in customer confirms their own offer
(stronger "in writing" than a bare link, and a stranger with the code
sees a 404) — and a tenant with NO register configured refuses outbound
entirely: the operator opts IN by configuring the wash, never out.
Named follow-ups: cold-prospect identity creation at confirmation;
segment → dial-list export with consent state; a telesales tab in the
dealer console.
