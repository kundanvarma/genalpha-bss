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

What the header could never do still holds: it only ever narrows what a caller
may do (a named agent meets the autonomy gate), it never grants anything.

**Proof:** `AgentIdentityTest` (the token's actor wins over the header and is
verified; a header alone is declared).

## Honest limits

- Whether the identity server puts an `act` claim into exchanged tokens depends
  on its token-exchange setup; until a mapper adds it, agents read as *declared*.
- Signed agent requests at the edge (Web Bot Auth, RFC 9421 signatures) are not
  verified yet — a follow-up in #422.
- Only the ontology reads the actor; other components see the delegated token's
  subject and roles, as before.
