# Agent identity — proven by the token, not by the agent's word

An AI agent that acts in the BSS — the care desk's assistant, a hired digital
worker, a customer's own shopping agent — is named on every receipt the ontology
writes. Until #422 that name came only from the `X-GenAlpha-Agent` header, which
the agent sets itself.

**Now there are two kinds of evidence, and the receipt says which.**

- **Verified.** When an agent acts for a person through a token exchange
  (RFC 8693), the token carries an `act` (actor) claim naming the agent. That is
  the identity server's statement, not the agent's. The ontology reads it, and
  the header can no longer rename the agent: the token wins.
- **Declared.** With no `act` claim, the header still names the agent — useful,
  but only the agent's own word.

Each receipt's authority line ends with `agent:verified` or `agent:declared`, so
an auditor can tell the two apart. The person the agent acts for stays the
receipt's principal either way.

**A declared name only ever narrows.** The autonomy gate judges a caller whose
agent is only declared twice — as if it had named no agent, and as the agent it
named — and keeps the stricter verdict. So:

- A person's token that names an agent is judged as that agent: an action a
  machine may not run alone is refused, even though the person could run it.
- A digital worker (a token carrying the `workforce:use` badge) is judged on its
  badge, whatever agent it names. Before this, a worker refused `issueCredit`
  could pass by naming a registered, trusted agent in the header; now only a
  name the token proves (`agent:verified`) is judged as that agent.
- An agent's own money budget applies to any machine — badge or named agent —
  even when the token also holds the approver role; only an approver who is a
  person decides alone.

**What this cannot catch.** A program that holds a *person's* token and leaves
the header out is, to the BSS, that person: nothing in the request says
otherwise, and it is judged exactly as the person would be. That is inherent
until agents act only through exchanged tokens that carry an `act` claim, or
through their own badged accounts. The header is how a well-behaved agent
narrows itself; it is not how a misbehaving one is detected.

**Proof:** `AgentIdentityTest` (the token's actor wins over the header and is
verified; a header alone is declared), `AutonomyGateTest` (a declared name never
loosens the gate; a badged worker naming a registered agent is still refused
what the badge alone is refused; a verified name is judged as itself),
`CreditLadderTest` (an approver's token driven by an agent meets the budget; an
approver person does not).

## Honest limits

- Whether the identity server puts an `act` claim into exchanged tokens depends
  on its token-exchange setup; until a mapper adds it, agents read as *declared*.
  That includes the hired digital worker (`hermes-worker`): its badged machine
  token carries no `act` claim, so any agent name it sends is only declared, and
  actions marked `autonomy: medium` (such as `issueCredit`) stay refused to it
  until its identity is proven by the token.
- A program holding a person's token without the header is judged as that
  person (above).
- Signed agent requests at the edge (Web Bot Auth, RFC 9421 signatures) are not
  verified yet — a follow-up in #422.
- Only the ontology reads the actor; other components see the delegated token's
  subject and roles, as before.
