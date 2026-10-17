# The model door — what a digital worker's AI costs, measured

*Arc #283, built 2026-10-17 (tickets #306, #307, #308, #310, #311).*

## The problem it solves

A digital worker — the Hermes agent the workforce hires — needs a model to think
with. Until this arc it held the tenant's own provider key and called the model
directly. That left three gaps that mattered to an operator:

- **Nobody could say what a worker cost.** The figure on the Workforce page was the
  worker's own report, and it mostly reported nothing: on 12 October, 46 of 251
  tasks carried any number at all.
- **Nothing could stop a worker spending.** The AI budget governs the BSS's own
  copilots; it never saw a worker's calls.
- **A worker's prompts left unredacted** — customer ticket text, read through the
  BSS's own tools — while every other model call in the BSS was redacted.

## What it is now

A worker calls its model **through the BSS**. The intelligence component holds the
provider key; the worker holds only a key of its own to the door.

**Hiring and firing.** When an operator hires a worker, the BSS issues it a door key
along with its badge. The worker container is given the door's address and that
key — never the provider's key. When the worker is fired, its key is revoked
**first**, so from that moment its model calls are refused, whatever happens to the
container. When the worker is restarted (for example after the operator changes its
model), it gets a new key and the old one stops working. A tenant whose worker model
the door cannot serve gets no worker at all: there is no direct path left.

**Every call, in order:**

1. the key says which tenant and which worker this is — never a header;
2. the tenant's AI kill-switch, the tenant's AI spend ceiling and the **workers' own
   spend ceiling** are checked; past any of them the call is refused with a message
   the worker treats as a hard wall, so its shift ends instead of retrying;
3. only the tenant's chosen worker model (and the small model the agent uses for its
   own side calls) is served;
4. the call is **redacted** — email addresses, phone numbers, account and card
   numbers, ids and labelled names become placeholders — with the same redactor every
   other model call in the BSS uses (a tenant that opted into raw exposure is the
   exception, and the ledger says so);
5. the provider answers; the placeholders are put back, **including inside the tool
   calls the model asks for**, so the worker's tool runs with the real value;
6. the call is **measured**: the provider's own token counts, priced at that model's
   listed rates (input, output and prompt-cache reads and writes apart), written to
   the AI ledger with the worker as the one who made it.

**On the Workforce page** the operator sees:

- **AI cost — measured** as the main figure, and the workers' self-reported figure
  beside it — a gap between them is itself worth noticing;
- each worker's own measured cost on its crew row;
- a **worker spend ceiling** next to the crew ceiling. Worker spend also counts
  toward the tenant's overall AI ceiling, so the operator's one total stays true,
  but a busy crew can never spend the copilots' share.

Copilots and workers choose their models independently: the copilots follow the
tenant's `ai-*` settings, the workers its `worker-ai-*` settings.

## Proof

- `ops/e2e/model_door_test.js` (tenant nova, on the fleet's mock model): a worker key
  is a governance act; a call reaches the provider with the tenant's key and never
  the worker's; the ledger matches the provider's own counts at the listed rates;
  the worker ceiling ends a shift with no retry; the kill-switch, the model check and
  the refusal of streaming hold; customer data reaches the provider as `<email#1>`,
  never in clear; an unknown or revoked key is refused.
- `ops/e2e/workforce_runtime_test.js` (#66), with a real Anthropic key: a hired
  worker holds only its door key; its real shift is **measured** in the ledger; a
  restart re-keys it; firing revokes the key first. `agentic_workforce_test.js` (#65)
  passes the same checks before and after the change.
- `ModelDoorApiTest` (11 tests), each guarantee seen to fail when broken on purpose.

## Honest limits

- **Anthropic only, for now.** The door speaks Anthropic's API. A worker on OpenAI or an
  OpenAI-compatible local model needs the second adapter (#309); until it lands such
  a tenant gets no worker, by design.
- **No streaming.** The door answers whole responses; workers are configured not to
  stream. A worker is not a person watching text appear, so nothing is lost, but it
  is a limit.
- **The ceiling can be overshot by one call.** It is checked before a call, and a
  call's cost is known only after it.
- **Cost is per worker and per shift, not per task.** One shift spans several tickets,
  and the door sees model calls, not tasks.
- **Prices are configuration.** A model with no listed price is charged at the default
  rate and its ledger row says `price=default`; the listed rates are the provider's
  public prices, not an invoice.
- **Cost is in the provider's currency (US dollars),** beside a self-reported figure the
  page labels in the tenant's currency.
- **Redaction finds what the shared redactor recognises.** A name in free prose with no
  label is not caught (as for every model call), and the agent's signed *thinking*
  blocks pass untouched.
- **The ledger keeps no content** for worker calls — only cost, model, outcome and how
  many values were redacted. That is a privacy choice, and it means the ledger cannot
  answer "what did the worker say".
- **Surge auto-hiring is unchanged** and spends real money: with a backlog and no crew
  ceiling it starts bench workers on its own. The door now measures and caps that
  spend; whether surge should be on by default is a separate decision.
