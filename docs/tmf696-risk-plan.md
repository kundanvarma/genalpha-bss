# TMF696 Risk Management — can this party be trusted with this order? — plan

*2026-08-03. The last high-value TMF on the gap list. Subscription fraud
is the acquisition-side twin of churn: the fleet scores who might LEAVE
(ChurnScorer) but has no concept of who shouldn't be let IN — grep
finds no risk/fraud construct anywhere. TMF696 is the standard face
(partyRiskAssessment + productOrderRiskAssessment), and the recon's
honest inventory decides what it may claim.*

## Research findings

- **The signals that are REAL**: unpaid bills per party (billing's
  `relatedPartyId&state=new` filter, machine-readable today); credit
  notes per party (repo finder exists; the API filter is a one-line
  addition); order velocity (orders per party with orderDate — the
  window computes client-side); party tenure (PartyRole.createdAt is
  the only tenure anywhere); the order's own shape (quantity, lines —
  already policy-context vars); the SESSION's verified-identity state
  (the JWT claim ordering already reads).
- **The signals the data does NOT know — and the face must not claim**:
  failed payments (refusals never persist a row — the payment states
  are authorized/captured/voided/refunded, no FAILED); whether a party
  has EVER BankID-verified (session claim only, nothing on the party).
  A party assessment therefore says nothing about verification; an
  ORDER assessment may carry it because the caller's session knows it.
- **Host: `intelligence`.** It already does per-party cross-service
  machine reads (BssApiClient), already ships a classical per-party
  scorer (ChurnScorer — the exact scaffolding), and its SA holds most
  needed roles (billing:read/admin, ordering:read); the gap is
  `party:read`. `insight` is consent-first web analytics (wrong
  posture, empty SA); `policy` is the enforcement seam, not a
  data-gatherer.
- **Enforcement: rules-as-data, not a hardcoded gate.** Ordering's
  policy context already carries verifiedIdentity and quantity vars,
  and JsonLogic rules already deny on them. The risk score rides in as
  two more context vars (`riskScore`, `riskLevel`) — fetched from the
  engine under ordering's machine identity, fail-open like the rest of
  the seam — and the OPERATOR decides the threshold as a policy rule.
  Nobody hardcodes "block at 60"; the deciding lives in config, where
  this fleet keeps its decisions.

## The design

`/tmf-api/riskManagement/v4` on intelligence:

- **`POST /partyRiskAssessment`** `{relatedParty:{id}}` — the engine
  reads, machine-to-machine, exactly the four party signals above,
  scores them with TRANSPARENT additive weights, and persists the
  assessment: score 0–100, level low|medium|high, and a
  characteristic block echoing every signal's raw evidence (unpaid
  bill count + amount due, credit notes, orders last 24h, tenure
  days) — the score can be recomputed by hand from its own body.
- **`POST /productOrderRiskAssessment`** — party signals PLUS the
  order's shape (totalQuantity, lineCount) and the session's
  verifiedIdentity (supplied by the caller, who knows it); a verified
  session REDUCES risk — BankID is the strongest anti-fraud signal
  the fleet has.
- **GET by id + list**: assessments persist (`risk_assessment` table,
  RLS) — an assessment is a fact a dispute may need later. All
  endpoints behind a new `risk:assess` authority (file + live): demo
  staff and the bss-ordering SA hold it; customers never see their
  own scores (a risk score is back-office, like a credit check).
- **Ordering**: a RiskClient (machine-token, fail-open) fetches the
  order assessment and adds `riskScore`/`riskLevel` to the policy
  context; a suite-seeded policy rule proves the deny path 422s with
  the rule's name. Billing grows the `relatedPartyId` filter on
  `GET /creditNote`.

## The phase (one)

Migration V22 (`risk_assessment`) + V23 RLS; RiskService + scoring;
BssApiClient += ordersOf/unpaidBills/creditNotesOf/partyRolesOf;
Tmf696Controller; security matcher (`risk:assess`); realm role
file+live (demo + bss-ordering SA; intelligence SA += party:read);
billing creditNote filter; ordering RiskClient + context vars; gateway
route (mvn package first). **Suite #82 `risk_test.js`**: an assessment
ECHOES the truth (the suite independently fetches kai's unpaid bills
and asserts the assessment's evidence matches); an order burst raises
the velocity signal and the score; a policy rule pinned just above
kai's live score denies his next order with 422 and the rule's name —
then the rule is removed and the same order passes (the operator
decided, as data, reversibly); a verified session scores LOWER than
the same order unverified; walls (customer 403 on assessments, nova
isolation). Regressions serial: policy, bankid, storefront.

## Shipped

*(recorded when the phase lands)*
