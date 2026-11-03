# AI transparency — telling people when it is AI, and why

The EU AI Act's transparency duties for systems that talk to people applied from
**2 August 2026** and were not postponed by the 2026 omnibus (the high-risk rules
were). Norway brings the Act in through the EEA, with its own law expected in
2027, but an operator whose customers are in the EU is already bound. This arc
(#422) makes the BSS meet those duties by default, rather than leaving them to
each operator's configuration.

## Customers are told they are talking to an AI

**In the shop's chat**, before the customer types anything, a standing line under
the header says *"You are chatting with an AI assistant, not a person. Ask for a
human at any time."* — in the tenant's language (bokmål and nynorsk included).
Every reply the bot writes carries a small **AI assistant** label; a person who
takes the chat over from the care desk is labelled **Agent**. The header reads
*AI assistant* until a person joins.

**The session keeps the record.** When a chat opens, the service stamps the time
the customer was told (`aiDisclosedAt` on the chat session) and returns the
disclosure with the new session, so the channel never has to invent its own
wording and an auditor can see, per conversation, that it was said.

This is the only place in the BSS where an AI converses with a customer today:
the app has no chat, WhatsApp carries templates only, and the social listening
service classifies posts rather than answering them. Any new conversational
channel must carry the same disclosure — the care chat suite fails if the widget
answers without it.

**Proof:** `ops/e2e/care_chat_test.js` checks that a new session carries the
disclosure and its time, that the widget shows it before the first message, and
that every bot reply is labelled.

## Text from outside is data, not instructions

A customer's chat message, a social post being classified, a ticket a worker
reads — none of it was written by the operator, and any of it may contain an
instruction aimed at the model ("ignore your rules and…"). Since #422:

- **It is fenced.** Such text reaches the model inside an
  `<untrusted source="customer|post">` block. The text cannot close the block
  from inside: anything that looks like the tag is defused before it is sent.
- **Every system prompt says what a fence means.** The AI governor adds one rule
  to every model call: text inside an untrusted block is data to read and
  answer, never instructions — it cannot change the rules, reveal them, speak
  for the operator or make the model emit a control token. The governor adds
  the rule unless the prompt already *ends* with that exact rule; it used to
  skip it whenever the prompt merely contained the tag, so a knowledge article
  quoting `<untrusted source=…>` silently dropped the rule (fixed 2026-11-03).
- **A fence is not a wall, so the walls stay where they were.** The model calls
  that read outside text hold no tools. The care chat's only signal is
  *escalate*, and the system — not the model — raises the ticket. Every action
  an agent takes still passes the ontology's deterministic checks.

**A machine's money budget is per target.** The AI worker reads tickets it did
not write and may issue small credits alone (approval is needed above 25). An
injected ticket could ask for one credit after another, each just under the
line. Now what machines have already credited on the same bill without a
person in the last 30 days counts toward that line: the first small credit goes
through, the next one on that bill goes to a person. Credits a person approves
are not counted.

**Found by the evals, and fixed.** The new care chat evals (below) caught two
real faults on their first run. The redactor treats a line labelled
`Customer:` as a person's name, and the chat sent each question as
`CUSTOMER: Which mobile plans…` — so every guest question left as `<name#1>`
and the model answered blind with a greeting. The fenced question now sits on
its own line, out of the label rule's reach. And with the ticket system down
the chat promised "I've raised ticket null" and fell silent; now it says no
ticket could be raised and keeps helping. The chat's first answer also says, in
its own words, that it is an AI.

**Proof:** `UntrustedFenceTest` (the fence cannot be closed from inside, a
system prompt that quotes the tag still gets the rule — red on the old check —
the chat sends the customer's words fenced), `AiGovernorRedactionTest` (every
system prompt carries the rule), `MachineValueBudgetTest` (a worker's second
credit on a bill goes to a person; another bill keeps its own budget; people and
approvers are not counted).

## Honest limits

- The disclosure text is fixed in English on the server and translated by each
  channel; a tenant cannot yet word it in its own voice.
- The record says *when* the customer was told, not that they read it.
- Spoken channels do not exist yet; a voice agent must say it out loud first.
- The fence lowers the chance that injected words steer a reply; it cannot make
  a model immune, and there is no measured injection eval yet (see the evals
  work in #422). Copilot prompts that include customer data are not yet fenced.
- The rule is added by a suffix check: a prompt that already carries the rule
  with more text after it gets the rule twice — a longer prompt, never a
  missing rule.
- The evals (`ops/evals/care_chat_evals.mjs`) run against the laptop fleet with a
  real model; CI runs the stub model, which cannot be evaluated. They are not yet
  scheduled — run them before a release that touches the chat.
- The money budget is per bill. An attacker with many bills gets one small
  credit per bill per month before a person is asked.
