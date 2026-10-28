# Benefit Choices — a plan that lets the household pick

A plan can now come with **Choices**: a number of partner services the household
picks for itself — films, audiobooks, music, an assistant — at no extra cost on
the bill. Pick one, swap it next month, give it back. The operator decides what
each plan grants and what each benefit costs; the household decides what to
spend them on. Each operator names a Choice in its own word: GenAlpha says
*Choices*, Taranga says *Picks*.

Built as the arc #352 (tickets #353–#362). The decisions are recorded in
[ADR 0027](adr/0027-benefit-choices-are-a-ledger-beside-the-order.md). The proof
is one browser suite, `ops/e2e/benefit_choices_test.js`, run through the gateway
with real tokens for customers, care agents, an AI agent, product managers,
approvers and finance.

---

## What the household sees

**In the shop**, under the profile menu, a *Choices* page (in the operator's
own word): how many are granted and by which plan, how many are in use, how many
are left, and the benefits on offer, each saying what it costs in Choices.
A pick is a click; the benefit shows *activating* until the partner confirms and
*active* after. A benefit in use can be swapped for another or released. A
benefit that holds its Choice (a subsidised phone pass, say) shows the date it
can be released from and offers no Release button before then.

**On Home**, one quiet line when the household has Choices it has not spent —
*"You have 2 of your Choices left"* — with a way to spend them. Nothing when
none are left, nothing when the operator does not offer Choices, and nothing
when the ledger cannot be read, because an unread pool is not an empty one.

What a household cannot do, it is told in words: a pick with no Choices left is
refused with the numbers; a swap beyond the quarter's limit names the day the
next one is possible; a plan change that would leave fewer Choices than are in
use names the benefits and how many to release first.

## What the care desk sees

In the customer cockpit, the household's Choices in the same words: what the
plans give, what is in use, what is left, and the swaps this quarter. A care
agent picks, swaps and releases for the customer. Picks and swaps go through
the governed actions **pickBenefit** and **swapBenefit**, so each one leaves a
receipt naming who did it. A **goodwill Choice** — one more for the household,
with a reason — is **grantGoodwillChoice**: someone holding the goodwill
approver role decides it on the spot; anyone else's request, and every AI
agent's, is filed on the approval desk for that person.

The desk assistant (an AI agent) may pick and swap on a customer's request
through the same actions, with its own receipt. It may only *ask* for goodwill.

## What the product manager sees

Catalog & Pricing → Products → **Benefit Choices**. Two things, no JSON:

- **What each plan grants**, per line, saved in place.
- **Benefits**: name, group, cost in Choices, the partner and their terms, what
  the partner charges a month, whether it holds its Choice and for how long, and
  the sentence the customer reads.

A benefit is an ordinary offering, so launch governance applies: in a governed
tenant a new benefit waits *In design* until an approver launches it, and the
person who made it cannot approve it.

## What finance sees

Billing & Revenue → Accounting → **Partner costs**: for a month, what the
operator owes each partner for the benefits households picked — partner,
benefit, how many households had it active, the amount, and the total, in the
tenant's currency. The same month is on the Journal as **partner benefits (cost
of sales)** against **a payable to the partner**, one entry per benefit per
month, so the page and the Journal never disagree. A completion that arrives
twice, or a partner that confirms twice, records nothing extra.

## What marketing sees

Every Choice event names the household, so two facts become customer traits
without any export: how many Choices are left, and which benefits are active.
**"Has unused Choices"** is then an ordinary audience (*Choices left ≥ 1*), and
so is "households who picked StreamCo". A campaign can start on *Benefit picked*
or *Choices granted*, and its message can name the benefit and the Choices left.
An operator whose old BSS still runs the picks feeds the same traits through
the bridge.

## How it works underneath

One component, **benefit-allowance**, counts Choices — granted, held, in use,
left — and keeps what each pick cost and what the partner is owed. It owns no
product: the catalog holds the terms, a TM Forum product order makes every
change (a pick adds, a release deletes, a swap is one order that does both),
inventory records what the household has. Ordering asks the ledger to **hold**
when an order is placed, to **settle** when it completes and to **release** when
it fails or is cancelled.

- Two picks racing for the last Choice cannot both win: every hold locks the
  household's pool first.
- The same order item asked twice is the same pick, never a second one.
- In a swap the old benefit stays active until the new one is; if the partner
  refuses the new one, the old one stays and the Choice comes back.
- The partner is asked before anything is sent again, so a late answer never
  becomes two activations.
- What a benefit cost, and what the partner charges for it, is kept as it was
  when it was picked; a later catalog change does not rewrite it.
- A hold is capped by the law of the tenant's country, kept as data.

**Per tenant** in `infra/tenants/tenants.yml`: `choices: true` switches it on,
`choices-label` is the word the screens use, `country` decides the hold law,
`partner-platform` picks the partner adapter. Off unless a tenant says so.

**Demo data**: `ops/seed/seed_choices.py` (GenAlpha — three granting plans,
StreamCo, Lydbok+ and a PhoneCo pass that holds its Choice for 12 months, the
swap limit) and `ops/seed/seed_choices_taranga.py` (Taranga — *Picks*, three
granting plans, six benefits in Streaming, Audio and AI, no holds). Every
partner brand is invented.

---

## Closing the gaps (#385)

The first build left a list of honest limits; the follow-on closed most of it:

- **The operator's own word everywhere.** The back office says *Picks* at
  Taranga ("1 Pick", "Picks per line"), from the tenant config every console
  loads. (#386)
- **Norwegian.** Every Choices string in the shop has a bokmål entry, with
  nynorsk where it differs, and the claims gate fails on any that is missing.
  (#387) Found on the way: forty nynorsk overrides had been silently replaced
  by their bokmål forms; they win again.
- **The mobile app** runs on Expo SDK 58 (#388) and has a Choices card on Home:
  how many are left, what is in use, pick and release — the same orders the shop
  places. (#389)
- **Partner invoices.** Finance records a partner's invoice for a month; Partner
  costs show owed beside invoiced and the difference in words; *Mark paid* posts
  the settlement — the partner payable against cash — once. (#390)
- **Eligibility is a rule.** "No new picks while in collections" is policy data
  the operator edits: ordering tells policy that an order picks a new benefit and
  whether the household has an open collections case. (#391)
- **"Can I pick this?"** Ordering answers before an order, from the order's own
  checks in the order's own sequence, holding nothing; the shop's cards say why
  a benefit cannot be picked instead of doing their own arithmetic. A benefit
  already in use is no longer picked twice. (#392)
- **The operator's own priority.** *Priority on my line* puts the household's
  existing line on the priority slice for a Choice, owes no partner, and gives
  the line back its plan's profile when released. (#393)


## Honest limits

- **The pool is per household, not per billing account.** The design asked for
  the billing account; the build keys on the customer party. For a household
  with one account they are the same. A business customer with several accounts
  would share one pool.
- **Merchant of record is not a setting.** Every partner benefit is sold as the
  partner's; an operator selling a partner's benefit as its own would need that
  switch.
- **Holds are off in Norway** until it is confirmed whether a held benefit is a
  binding period (bindingstid). Taranga therefore seeds no held benefit.
- **Eligibility leaves no decision record.** The rule decides and its words reach
  the customer, but policy keeps no stored decision with the rule's version.
  When billing cannot be read, the collections fact is absent and the rule does
  not fire — an outage never refuses a pick. Ordering's machine identity reads
  billing with `billing:read`, broader than the one question it asks.
- **The preview skips risk and credit.** Each records an assessment when asked
  and a preview must leave no trace, so an operator rule built on a risk score or
  a credit decision is the one way a preview's yes can become an order's no.
- **Partner invoices are entered by hand.** Nothing receives them from the
  partner; a paid invoice is booked against cash, with no bank or AP system
  behind it. Partner cost still counts whole months, never prorated.
- **Priority on a Priority plan buys nothing.** A household whose plan already
  rides the priority slice can still pick the add-on; it changes nothing (and
  never takes the plan's priority away), but nothing refuses it yet.
- **"Activated" and "activation failed" are not events of their own.** A pick
  going active is *picked*; a refused one gives its Choice back as *released*.
  The partner's own reason sits on the service, not on the event.
- **The background AI worker cannot use Choices.** Its badge carries the
  goodwill approver role (it would approve its own goodwill) and not the
  ordering role, so it was deliberately left out; the desk assistant can.
- **Server-side reasons are English.** The shop's own strings are translated;
  a refusal or a preview reason written by the ledger, ordering or a policy rule
  is in the words it was written in.
- **The bus does not keep a household's events in order** (they are keyed by
  event id), so the *Choices left* trait keeps the newest by event time and
  ignores an older one that arrives late.
- **Nothing here is on the hosted demo box** beyond the first build: the follow-on
  reaches it with the next deploy.
