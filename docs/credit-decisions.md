# Credit decisions: the reason, the explanation, and a person to ask (#422)

*Built November 2026 on the credit-decision port from the Norway rails arc
([norway-rails-plan.md](norway-rails-plan.md)). Component: `product-ordering`.*

## Why this exists

When a customer signs up to a plan they pay for after use, ordering asks a
credit bureau about them. Until now the shop stored only *what* the bureau
answered, and a customer whose order was declined saw two words: "Awaiting
approval". That is not enough any more:

- **GDPR Art. 22 and Art. 15(1)(h)**, as the EU Court of Justice read them in
  *Dun & Bradstreet Austria* (C-203/22, 27 Feb 2025) — a case about a mobile
  contract refused on a credit score: the person is owed a concise,
  intelligible explanation of the procedure and the principles actually
  applied, and the right to have a person look at it.
- **The Consumer Credit Directive 2** (EU 2023/2225, applies from
  20 Nov 2026): for an automated creditworthiness assessment, the right to
  human intervention, to an explanation, and to contest the decision.
- **The AI Act, Annex III** — credit scoring of natural persons is high-risk
  (obligations from 2 Dec 2027 on the current timetable); a deployer must
  keep a person in the loop and tell people a system is deciding about them.

## What an operator gets

**Every decision keeps its reason.** Beside the decision, the score band and
whether a payment remark exists, ordering now stores *why*, in two forms: a
short code a screen can translate, and a plain sentence a person can read.
The reason is worked out only from what the bureau actually told us:

| Code | When | Sentence (example) |
|---|---|---|
| `PAYMENT_REMARK` | a decline, and the bureau reports a payment remark | "The credit bureau reported a registered payment remark (an unpaid debt on record), and placed you in score band E, below what this plan needs." |
| `SCORE_BELOW_THRESHOLD` | a decline with no payment remark | "The credit bureau placed you in score band E, below what this plan needs." |
| `SCORE_NEAR_THRESHOLD` | the bureau's "review" answer | "The credit bureau placed you in score band C, close to what this plan needs." |
| `SCORE_MEETS_THRESHOLD` | an approval | "The credit bureau placed you in score band A, which meets what this plan needs." |
| `CREDIT_FROZEN` | the customer has frozen their credit file | "Your credit information is frozen at the credit bureau, so it could not be assessed." |
| `UNRECOGNISED_ANSWER` | an answer the port does not know | "The credit bureau gave an answer this shop does not recognise." |

Each decision is also tied to **the order it was taken for**, so the
customer can ask about *that* order.

**A decline never refuses on its own.** It was already true that a decline
puts the order on hold for a person (the order carries `[credit-review]`);
the customer is now told so, in those words.

**Before checkout**, a signed-in customer with something in the cart sees a
short notice: *"Before we accept a plan you pay for after use, we check your
credit with the credit bureau. The check is automated, but it never refuses
you on its own: a person reviews every decline. You can ask why, and ask a
person to look again, from My orders."* Whether the shop checks credit at all
is ordering's answer (`bss.credit.enabled` / `bss.credit.tenants`), never the
shop's; when the tenant does not check, nothing is shown.

**On My orders**, a declined order shows *Why your order is waiting*: the
reason, the score band, that a person reviews every decline, what the check
used (who you are, the score band, whether a payment remark exists, what the
check was for), that the bureau's report is never received or kept — and a
button, *Ask a person to review*, with room for a note ("the debt was paid in
September"). Everything is in English, bokmål and nynorsk.

**Asking for a person** files one **trouble ticket** (TMF621, type
`creditReview`, severity major) in the care queue, naming the customer and
the order, carrying the reason and the customer's note, and saying what the
agent should do: release the order, or cancel it with the customer. The
ticket is filed under the **customer's own login**, so it is theirs and they
can follow it on their Support page. Pressing the button twice files one
ticket: the second press answers *"You asked a person to review this"* with
the same ticket. If the care queue cannot take the ticket, the customer is
told to try again and nothing is half-recorded.

**On the care desk**, Customer 360's credit card now shows each decision's
reason and, when the customer has asked, *Customer asked for a person to
review this*.

## For integrators

| | |
|---|---|
| `GET /tmf-api/productOrderingManagement/v4/creditDecision/notice` | The checkout notice: `checked`, `bureau`, `purpose`, `dataUsed`, `notice`. |
| `GET …/productOrder/{id}/creditDecision` | The explanation for one order. A customer gets only their own orders — anyone else's answers **404**, exactly as reading the order does. Staff are unscoped. |
| `POST …/productOrder/{id}/creditDecision/review` | Body `{ "note": "…" }` (optional, up to 1000 characters). **201** when filed, **200** with `alreadyRequested: true` when it already was; **400** for an approval or a freeze (nothing to contest); **502** when the ticket could not be filed. |

- The order, the owner and the tenant come from the path, the token and the
  store; the body is a record that can carry only a note.
- `CreditDecisionRecordedEvent` now also carries `orderId`, `reasonCode` and
  `reason`. A new event, `CreditDecisionReviewRequestedEvent`, goes out
  through the outbox on `bss.ordering.events` when a customer asks for a
  person. **No marketing consumer reads either, on purpose** — insight,
  campaign and bss-bridge pick events by name and ignore these; credit data
  must not feed segments or journeys.
- Migration `V13__credit_decision_reason.sql` adds five columns to
  `credit_decision` (order, reason code, reason, review time, ticket); the
  table's existing row-level-security policy (`V11`) covers them.
- Settings: `CREDIT_BUREAU_NAME` (default "the credit bureau") is the name
  customers read; `TICKET_BASE_URL` points ordering at trouble-ticket
  (compose and the Helm chart set it).
- Proof: `CreditExplanationApiTest` (8 MockMvc tests) and the *CREDIT WHY*
  leg of `ops/e2e/norway_rails_test.js`.

## Honest limits

- **The explanation is category-level, not bureau-level.** We can say *which
  kinds* of data were used and what the bureau handed back (a band, a remark
  flag, a decision). We cannot say which factors the bureau weighed inside its
  own model, because the port deliberately never receives the report. The
  customer is told to ask the bureau for that; under C-203/22 the bureau owes
  its own explanation.
- **"What this plan needs" is the bureau's line, not ours.** The service does
  not compare the band to a threshold of its own; it asks the bureau under one
  purpose (`postpaid-signup`) for every plan, so "this plan" means "a plan you
  pay for after use", not a per-plan setting.
- **An operator's own rules can still refuse automatically.** A policy rule
  that reads `creditDecision` or `creditScoreBand` and denies an order answers
  `POLICY_DENIED` at checkout: no order is created, so there is no order to
  explain and no button. This arc covers the bureau's decline (which only ever
  holds); a rule that refuses on credit data is the operator's decision, and
  under Art. 22 it needs its own human route. Nothing stops an operator writing
  one today.
- **A freeze is a hard stop and is not contestable** — it is not a decision
  about the customer's creditworthiness; the shop offers the prepaid path, as
  before. The order is rolled back, so there is no explanation page for it.
- **Fail-open leaves nothing to explain.** When the bureau is unreachable, the
  order proceeds unassessed and no decision is recorded; the explanation says
  "No credit check was recorded for this order". Decisions stored before this
  change have no order link and read the same way.
- **The shop shows the panel only on a held (declined) order.** A "review"
  answer that let the order through is explained by the API but not shown in
  the shop. The notice and the panel are in the web shop only — not in the
  mobile app, the dealer portal or a CSR-placed order — and the notice is shown
  to signed-in customers (an anonymous shopper signs in before placing the
  order, and sees it then). If the notice cannot be read, the shop shows
  nothing rather than guess whether credit is checked.
- **A lost answer can mean a second ticket.** One request per order is held by
  a row lock, and a ticket the care queue refused rolls the request back so the
  customer can retry. If the care queue *took* the ticket but the answer never
  came back (a timeout), the retry files a second ticket.
- **The bureau's name is one per deployment** (`CREDIT_BUREAU_NAME`), not per
  tenant.
- **Norway.** CCD2 is an EU directive and is not yet part of the EEA
  Agreement; in Norway the *finansavtaleloven* (2020) and the GDPR (through the
  *personopplysningsloven*) still govern. Whether a postpaid telecom
  subscription is "credit" under CCD2 at all is a legal question this arc does
  not answer; it builds to the stricter reading.
- **The AI Act asks more of a deployer than this.** The scoring is the
  bureau's; as the deployer, the operator also owes logs kept for the system's
  use, a fundamental-rights impact assessment where required, and staff trained
  to oversee it. This arc gives the human review and the explanation, not
  those.
- **Not yet seen in a browser.** The suite leg proves the API through the
  gateway with a real token; the shop panel and the checkout notice were built
  and compiled but not run against the fleet or looked at in a screenshot, and
  the suite has not been run for this change.
