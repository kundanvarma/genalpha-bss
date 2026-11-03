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

**A decline is never final without a way to a person.** The bureau's decline
puts the order on hold for a person (the order carries `[credit-review]`). An
operator's own policy rule can still refuse an order outright — "no postpaid
plan after a decline", say — and then there is no order: it rolls back. The
decision does not. It is stored before the rules run, it records that the
operator's rule refused the order and with which words, and it stays
explainable and contestable by its own id. The refusal the shop receives
(`422 POLICY_DENIED`) links to it, so the customer reads why and can ask a
person right there in the cart. So the true sentence is: *held declines wait
for a person; a decline the operator's rule refuses can still be explained
and reviewed.* "A person reviews every decline" was not true, and is no
longer written anywhere.

**What happened to the order is read off the order.** The explanation says
what the decision actually did — on hold, released by a person, cancelled,
refused by the shop's own rules, not placed, or went ahead — from the order
as it stands, never from the bureau's answer alone. A decline care later
released no longer says "on hold"; a borderline answer an operator held no
longer says "went ahead"; the care ticket says the same.

**Before checkout**, a signed-in customer with something in the cart sees a
short notice: *"Before we accept a plan you pay for after use, we check your
credit with the credit bureau. The check is automated, but a decline is never
final without a way to a person. You can ask why, and ask a person to look
again, from My orders."* Whether the shop checks credit at all is ordering's
answer (`bss.credit.enabled` / `bss.credit.tenants`), never the shop's; when
the tenant does not check, nothing is shown.

**On My orders**, an order whose credit decision is worth explaining shows
*About the credit check*: what happened to the order, the reason, the score
band, the route to a person, what the check used (who you are, the score
band, whether a payment remark exists, what the check was for), that the
bureau's report is never received or kept — and a button, *Ask a person to
review*, with room for a note ("the debt was paid in September"). **Ordering
decides which orders show it** — a decline or a borderline answer, held or
not, cancelled or not, or one the customer already asked about — and says so
on the order itself; the shop only shows what it is told. Everything is in
English, bokmål and nynorsk.

**Asking for a person** files one **trouble ticket** (TMF621, type
`creditReview`, severity major) in the care queue, naming the customer, the
order (when there is one) and the decision itself, carrying the reason, what
happened to the order and the customer's note, and saying what the agent
should do: release or cancel a held order with the customer, or help them
order again. The ticket is filed under the **customer's own login**, so it is
theirs and they can follow it on their Support page. Pressing the button
twice files one ticket: the second press answers *"You asked a person to
review this"* with the same ticket. If the care queue cannot take the ticket,
the customer is told to try again. A **slow** care queue cannot file two
either: the request is claimed and committed first, the ticket is filed with
no database lock held, and before filing, ordering looks for a ticket that
already names this decision — so a queue that took the ticket but answered
after ordering stopped waiting is found on the retry, not filed again.

**A business admin** (a company's administrator ordering for its staff) reads
and contests only the decisions of members of its own organization — the
same wall order creation applies; anyone else's answers 404.

**On the care desk**, Customer 360's credit card now shows each decision's
reason, when the customer has asked, *Customer asked for a person to
review this*, and, when the operator's rule refused the order, *Order not
placed — refused by the operator's rule* with the rule's words.

## For integrators

| | |
|---|---|
| `GET /tmf-api/productOrderingManagement/v4/creditDecision/notice` | The checkout notice: `checked`, `bureau`, `purpose`, `dataUsed`, `notice`. |
| `GET …/productOrder/{id}/creditDecision` | The explanation for one order. A customer gets only their own orders — anyone else's answers **404**, exactly as reading the order does; a business admin only its own organization's members. Staff are unscoped. Carries `decisionId`, `show` (should a channel show it), `outcomeCode` (`held`, `released`, `cancelled`, `refusedByRule`, `notPlaced`, `wentAhead`, `frozen`) and `outcome`. |
| `POST …/productOrder/{id}/creditDecision/review` | Body `{ "note": "…" }` (optional, up to 1000 characters). **201** when filed, **200** with `alreadyRequested: true` when it already was (no `ticketId` yet while a first request is still with the care queue); **400** for an approval or a freeze (nothing to contest); **502** when the ticket could not be filed. |
| `GET …/creditDecision/{decisionId}` and `POST …/creditDecision/{decisionId}/review` | The same two by the decision's id, for a decision whose order was never placed. Same walls; `refusal` carries the operator's rule's own words. |
| `GET …/productOrder`, `GET …/productOrder/{id}` | A house field `creditDecision: {id, href}` beside the TMF622 body when ordering says the decision should be explained; absent otherwise. |
| `422 POLICY_DENIED` on `POST …/productOrder` | A house field `creditDecision: {id, href}` beside the TMF error when the refusal left a decline or borderline decision the customer can ask a person about. |

- The order, the owner and the tenant come from the path, the token and the
  store; the body is a record that can carry only a note.
- The review ticket's `relatedEntity` now names the decision too
  (`@referredType: CreditDecision`) — the reference a retry finds it by, since
  trouble-ticket has no `externalId` filter.
- `CreditDecisionRecordedEvent` now also carries `orderId`, `reasonCode` and
  `reason`. A new event, `CreditDecisionReviewRequestedEvent`, goes out
  through the outbox on `bss.ordering.events` when a customer asks for a
  person. **No marketing consumer reads either, on purpose** — insight,
  campaign and bss-bridge pick events by name and ignore these; credit data
  must not feed segments or journeys.
- Migration `V13__credit_decision_reason.sql` adds five columns to
  `credit_decision` (order, reason code, reason, review time, ticket), and
  `V14__credit_decision_refusal.sql` two more (when the operator's rule
  refused the order, and its words); the table's existing row-level-security
  policy (`V11`) covers them.
- Settings: `CREDIT_BUREAU_NAME` (default "the credit bureau") is the name
  customers read; `TICKET_BASE_URL` points ordering at trouble-ticket
  (compose and the Helm chart set it).
- Proof: `CreditExplanationApiTest` (17 MockMvc tests: the walls, the refusal,
  the outcomes, when to show, the claim), `CreditReviewTicketIdempotencyTest` (2, against
  a real HTTP stand-in that creates the ticket and then answers too late) and
  the *CREDIT WHY* leg of `ops/e2e/norway_rails_test.js`.

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
- **An operator's own rules can still refuse automatically — but not finally.**
  A policy rule that reads `creditDecision` or `creditScoreBand` and denies an
  order answers `POLICY_DENIED` at checkout and no order is created; the rule
  semantics are the operator's and are unchanged. What changed is that the
  decision survives the refusal, says so, and can be explained and put to a
  person by its id — the 422 links it, and the cart shows it. Ordering cannot
  tell whether the rule actually read the credit data (policy answers only
  "denied" and a message), so any refusal after a credit check is recorded the
  same way; for an approval it is recorded but not linked, because there is
  nothing about the credit check to contest.
- **A refused decision is only reachable at the moment of refusal in the
  shop.** The cart shows it when the 422 arrives; My orders lists orders, and a
  refused order is not one, so a customer who leaves the cart without asking
  must find their way back through care (Customer 360 shows the decision, its
  refusal and the rule's words). A "my credit decisions" page is a follow-up.
- **"Refused by the rule" is recorded only for a policy refusal.** An order
  that rolls back for another reason after the credit check (a payment or
  stock failure, a benefit conflict) leaves a decision that reads "Your order
  was not placed." — still explainable and contestable by its id, but nothing
  links the customer to it.
- **A freeze is a hard stop and is not contestable** — it is not a decision
  about the customer's creditworthiness; the shop offers the prepaid path, as
  before. The order is rolled back, so there is no explanation page for it.
- **Fail-open leaves nothing to explain.** When the bureau is unreachable, the
  order proceeds unassessed and no decision is recorded; the explanation says
  "No credit check was recorded for this order". Decisions stored before this
  change have no order link and read the same way.
- **The notice and the panel are in the web shop only** — not in the mobile
  app, the dealer portal or a CSR-placed order — and the notice is shown to
  signed-in customers (an anonymous shopper signs in before placing the order,
  and sees it then). If the notice cannot be read, the shop shows nothing
  rather than guess whether credit is checked.
- **One ticket per decision rests on a lookup, not on the ticket API.**
  trouble-ticket has no `externalId` filter, so the retry lists the customer's
  `creditReview` tickets (the newest 100) and matches the decision named in
  `relatedEntity`. A lookup that fails fails the request — guessing "none" is
  how a duplicate is filed. Two presses within the same instant while the care
  queue is slow get "asked for" without a ticket number until the first lands;
  a claim whose ticket call died is taken over after two minutes. A ticket
  someone edits to drop that reference would no longer be found.
- **The outbound read timeout was not in force before this change.** Since
  Boot 4 the `spring.http.client.*` keys are deprecated and not bound; with
  them a 400 ms read timeout let a 1.5 s answer through. product-ordering now
  uses `spring.http.clients.*`; the other components' `application.yml` files
  still carry the old key and are not changed here.
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
  the suite has not been run for this change. That still holds after the
  review fixes (November 2026): the refusal panel in the cart, the server-led
  panel on My orders and the care desk's refusal line were built and compiled,
  not run against a fleet; the suite's *CREDIT WHY* leg gained the `show`,
  `outcomeCode` and order-link checks but has not been run, and it has no leg
  for a policy refusal (it would need a rule authored in the policy component).
