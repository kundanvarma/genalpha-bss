# Agentic engagement — stop gamifying attention, start publishing proof

**Status:** PLAN (authored 2026-08-22; build on trigger) · **Depends on:** the agent-commerce gate (off | discovery | full, born off per tenant), the dealer/partner channel (commission accrual, hardening, clawback), the referral engine (G1), TMF696 risk, the receipts culture (holdout lift, published odds, audit ledgers)

## The thesis

An AI shopping agent does not feel dopamine. Wheels, streaks and scarcity
theater are worthless against a buyer that optimizes terms. What an agent
"engages" with is machine-legible trustworthiness: verifiable prices,
published odds, provable reward terms, exit without hostage-taking. Two
consequences, both structural:

1. **An agent that brings a subscriber is a DEALER, not a customer.** The
   right economic contract for agent platforms already exists in this
   codebase: commission that accrues pending, hardens after the withdrawal
   window, and claws back honestly. An agent is a dealer with an API instead
   of a counter.
2. **The honest game is the only gamification that survives agents.** The
   receipts this platform already keeps — holdout-measured lift, transparent
   odds, one-number pricing — stop being ethics and start being RANKING
   SIGNALS for the agents that assemble shortlists. "The operator whose
   claims an agent can verify" is a category of one.

## The phases

### A1 — Agents are dealers (the channel contract)

Agent-attributed conversions flow into the commission machinery: the
agent-commerce gate already knows which agent platform is calling; a
registered agent platform becomes an org party with a partner agreement
(the dealer shape), and a completed order attributed to it accrues
commission — pending, hardened, clawed back on early churn, exactly like a
store. The wholesale desk gains an "Agent channels" readout: acquisition,
commission, and clawback per platform. Referral velocity guards get an
agent-specific class: machine-scale abuse is the same farm signal at a
different clock speed.

### A2 — The referral artifact (human relationships, machine carriers)

A member-get-member code's real payload is proof of a human relationship —
which survives agents if it becomes a portable, verifiable artifact. The
agent-facing catalog (discovery mode) publishes the referral offer as
structured data (both-sides reward, terms, expiry), and a joiner's agent may
present a friend's code on their behalf; self-scoping, one-per-joiner and
pay-on-first-completed-order are unchanged. The human said "use Rita's
code"; the agent just carried it honestly.

### A3 — The proof surface (the trust API)

A crawlable, signed `/proof` face that answers what an agent actually ranks
on: the published odds of every chance mechanic; the holdout-measured lift
an engagement program claims (or the honest absence of a claim); the
one-number pricing attestation (what the subscriber pays, with no
agent-invisible fees); the Right-Plan Guarantee's running count. Nothing new
is computed — the receipts exist; this phase only makes them legible to
machines. The bet: agents will rank verifiable operators above cheaper
unverifiable ones for risk-averse users, and this platform can be first to
find out.

### A4 — Values metadata + governance parity

Klubbdugnad and community goals published as structured cause metadata, so
an agent instructed "prefer the operator that funds my club" can honor it —
community belonging is the one loyalty input a rival cannot undercut on
price. And the guardrails get governance parity with the AI governor:
per-agent-platform budget caps, a kill switch per platform, TMF696 risk
class for agent-attributed conversions, and every agent decision leaving
the same receipts a human channel would.

## Honesty rules (non-negotiable, house style)

- **No agent-only pricing.** An agent never sees a price or a fee structure
  a human cannot see; the proof surface attests to it.
- **Attribution is provable, not claimed.** An agent platform's commission
  is backed by the same order-completion evidence as a dealer's — no
  last-click mythology.
- **Born off.** Every phase is per-tenant opt-in behind the existing
  agent-commerce gate; a tenant that never turns it on never changes.
- **Humans keep the keys.** Agent-channel registration, commission terms
  and the kill switch are staff decisions with receipts — the same approval
  posture as every other automation in this platform.
