# The rules an offering is priced and blocked by, on the offering

*2026-09-26. Ticket #157. Built as the ticket's option B — a small backend
read plus a read-only panel — because option A could not answer the question
the ticket was written to answer.*

## What was wrong

A rule reaches an offering through the **Item** field on the rule. That
direction has always worked. The reverse did not exist: open an offering and
nothing on the page told you a rule pointed at it.

It was found while writing the step-by-step discount guide. A product manager
could put 50 NOK off a phone, open that phone, and see no trace of the
discount anywhere on it. Three consequences, all of them ordinary days at a
desk:

- *"Why is this cheaper than I expect?"* could not be answered from the
  product — only by reading every rule on the Rules page and matching ids.
- **Retiring an offering** gave no warning that rules still pointed at it.
- With four discount mechanisms across three departments, the offering is the
  one place they all meet, and it was the one place that said nothing.

The model is right and was not changed. A rule can apply to a basket, a
company or a customer type, so it cannot live on one offering. What was
missing is only the **read-back**.

## What was built

**One authenticated read.**
`GET /tmf-api/policyManagement/v4/policyRule/referencing?offeringId=…` returns
every rule whose authored condition names that offering — pricing **and**
blocking, enabled **and** disabled — each with its name, domain, effect,
adjustment, priority, message, state and `href`. It carries **no condition**:
it answers *which rules touch this offering*, never *on what terms*. The terms
stay on the Rules page, which is where they are edited.

**One read-only panel**, *"Rules that name this offering"*, at the foot of the
offering form. One card per rule: the rule's name, a word for its state (`Live`
/ `Switched off`, with the colour only agreeing with the word), what it does in
a sentence, the operator's own message, and **Open rule**, which lands on the
Rules page narrowed to that rule. An offering with no rules says so in a
sentence rather than showing an empty box. There is no input anywhere in it —
it is a window, not a second rule editor.

## Why option A would not have done

The ticket offered reusing `GET /price/teaser`. It walks
`enabledPricingRules()`, so it shows **enabled pricing rules only**. Suite #245
makes the difference the first thing it proves: two rules name the fixture
offering, one pricing and one blocking; the teaser sees **one**, the new read
sees **two**. Then the pricing rule is switched off — the teaser sees
**nothing at all**, and the panel still lists it, marked *Switched off*.

A disabled rule is exactly what an operator is hunting when they ask why
nothing is happening, and a live one is the warning they need before retiring
the offering. Neither is reachable through the teaser at any state.

Both halves were checked by breaking them, because a gate nobody has watched
fail is not a gate. A policy service rebuilt to walk `enabledPricingRules()`
fails the suite at its first read rung. One rebuilt to keep the wide search but
skip disabled rules passes five rungs and fails at **OPTION B**. A console
rebuilt with the blind-spot sentence quietly shortened fails at **THE PANEL**.
The round-trip test goes red if `"enabled":false` is ever left off the wire, and
the ratchet goes red if the offering form grows past 300 lines. Each was
restored and the suite ran green, ten rungs, three times in a row.

## The door this read is not

The teaser is the **anonymous shop window** — that is its job, and it is
unchanged. This read is back-office configuration and sits behind
`policy:read`, the same authority the Rules page's own list requires, so a
negotiated company rate cannot reach a reader who has not been granted it.
The suite proves the three states together on every run: anonymous is **401**,
a signed-in caller without `policy:read` is **403** — asserted to be the *same*
status that caller gets from the rules page itself, not merely a refusal — and
the teaser still answers **200** to nobody at all.

## A claim the panel stopped making

The first build printed *"50 EUR off"* for an amount adjustment, from the
console's tenant-currency fallback, directly beside the operator's own message
saying *50 NOK*. A rule stores a bare number; the currency is whatever the cart
it fires on is denominated in. The panel now says *"a flat 50 off"* — which
says it is an amount and not a percentage, and claims nothing the rule does not
hold. The suite fails if a currency code appears outside the operator's own
quoted message. It was caught by looking at the screenshot.

## Proof

- **Suite #245**, `ops/e2e/offering_rules_panel_test.js` — ten rungs through
  the gateway with a real token: the teaser gap, the blind spot, the gate,
  names-without-terms, the panel, that the form still saves, the disabled rule
  still listed, the way to the rule, and the empty state. It creates an
  offering, a price and three rules and deletes all five — then sweeps
  offerings, prices and rules by its own run tag, so a failure halfway still
  leaves the tenant as it was found.
- **The form still saves.** A new field on a shared form is one save away from
  breaking it, so the suite presses *Save changes* on the offering and reads
  the row back: name, lifecycle and price unchanged, and nothing named after
  the panel written to it. The panel's `get()` returns `undefined` and the
  editor drops undefined values — this rung is what keeps that true.
- `DtoRoundTripTest.referencingRule_carriesTheStateAndNotTheCondition` pins the
  bytes, including that `"enabled":false` can never be left off.
- The architecture ratchet and the claims gate are green.

## The blind spot, and why it is on the page

The rule form shows its **Item** field for exactly one rule kind — *"Price:
discount when the cart has an item"*. Every other kind is conditioned on
something else: a company, a configured choice like a colour, a verified
identity, a member count, or nothing at all. Those rules discount this
offering's price **without ever naming it**, so no read from the offering's end
can list them. That is a property of how rules are authored, not a gap in this
read.

Which makes the wording load-bearing. An empty panel saying *"no rule prices
this offering"* would have been false while a negotiated company rate quietly
took 20% off it. The panel says **"No rule names this offering"**, and both its
full and its empty state carry the sentence underneath:

> Only rules that name this offering. A discount for everyone, a company's
> negotiated deal or a campaign on a configured choice applies without naming
> one — look for those on the Rules page. Volume pricing sits on the price
> lines.

The suite holds this to account. It authors a **live** company rate beside the
two named rules — conditioned on an organization that does not exist, so it
moves no real price — and asserts that neither the panel nor the teaser lists
it, and that the sentence above is on the page in both states. A panel that
quietly dropped that line would go red.

## Honest limits

- **It reads the condition, and only the condition.** See the blind spot above:
  a rule that discounts this offering by company, colour, verified identity or
  volume never mentions it, and is not listed. The panel says so; it cannot do
  better without a model change the ticket explicitly ruled out.
- A personalization rule that **pins** an offering does so through its
  `experience` document rather than its condition, so it is not listed either.
  Personalization rules that name an offering in their *condition* are.
- **The match is a substring, as the teaser's always has been.** An offering id
  is a UUID in every path that writes one, so this is exact in practice; it is
  not a foreign key, and nothing stops a rule from mentioning an id in prose.
- **It does not unify the four discount mechanisms.** Volume pricing lives on
  the price lines and campaign offers on the Marketing desk. The panel covers
  rules only and says so on the page, in the last line of the panel.
- **It does not say whether a rule would actually fire.** A rule that names the
  offering may still need a verified identity, a company, or a minimum
  quantity. The panel says a rule points here; the Rules page's dry run says
  what it does to a given basket.
- **No paging.** Every rule naming one offering is returned in one answer. The
  tenants we run hold single-digit numbers of rules per offering; a tenant with
  hundreds would want a page, and would notice before we did.
- **The panel is not on the Rules page in reverse.** A rule still does not list
  the offerings it touches. That is the same read from the other end and was
  not built.
