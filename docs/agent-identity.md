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
- **Verified at the edge.** When an agent signs its requests (Web Bot Auth, see
  below) and the gateway checks the signature against a key this deployment
  trusts, the gateway names the agent's operator in `X-Verified-Agent`. The
  ontology counts that as verified too. The token's `act` claim still wins if
  both are present.
- **Declared.** With neither, the header still names the agent — useful, but
  only the agent's own word.

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
verified; a gateway-verified signature is verified and outranks the header; a
header alone is declared), `AutonomyGateTest` (a declared name never
loosens the gate; a badged worker naming a registered agent is still refused
what the badge alone is refused; a verified name is judged as itself),
`CreditLadderTest` (an approver's token driven by an agent meets the budget; an
approver person does not).

## Signed agents at the edge

Agents on the open web are starting to sign their requests, so a site can tell
a real operator's agent from anything that merely calls itself one. The
standard is **Web Bot Auth** (IETF draft-ietf-webbotauth-httpsig-protocol-01),
which profiles HTTP Message Signatures (RFC 9421). The agent sends three
headers:

- `Signature-Agent` — where its operator publishes its keys (an https URL);
- `Signature-Input` — what was signed and when: at least the host it is
  calling (`@authority`), the `Signature-Agent` value, a start and an expiry
  time, the key id (the key's JWK thumbprint), and the tag `web-bot-auth`;
- `Signature` — the Ed25519 signature over all of that.

**The gateway checks every such request before anything else sees it.** It
recomputes what was signed, checks the time window (no more than 24 hours
long, not expired, not dated in the future beyond a minute of clock
tolerance), finds the key among the operators this deployment trusts, and
checks the signature. Then:

| What arrived | What downstream sees |
|---|---|
| A valid signature from a trusted operator | `X-Verified-Agent: <operator name>` and `X-Verified-Agent-Key: <key id>`, both written by the gateway. Any `X-GenAlpha-Agent` the agent sent is removed — the signature names it now. |
| A signature that fails — wrong host, altered, expired, a key nobody trusts, a URL nobody configured, unreadable | No agent headers at all. The request carries on as an ordinary one with no agent identity; it is never answered with an error for this. A broken signature proves nothing, so it earns nothing, not even the agent's own declaration. |
| No signature | Any `X-Verified-Agent` or `X-Verified-Agent-Key` the client sent is removed. `X-GenAlpha-Agent` passes as the agent's own declaration, as before. |

**Which header downstream may trust:** `X-Verified-Agent` (and its key id).
No client can set it — the gateway removes it from every request that
crosses it and writes it only after a signature holds. `X-GenAlpha-Agent`
stays a declaration. Neither grants anything: a person's own token still
decides what may be done, and a named agent still only narrows it.

**Which operators are trusted is the deployment's choice**, written in the
gateway's configuration (`bss.web-bot-auth.operators`) and empty by default.
Each operator has a name (what receipts and logs show), the URL its agents put
in `Signature-Agent`, and its keys: written in directly as public keys, or a
key directory URL (`/.well-known/http-message-signatures-directory`) that the
gateway fetches, keeps for an hour, and re-reads at most once a minute however
many unknown keys arrive. The gateway only ever fetches a directory written in
that configuration — never a URL a request names — and only over https.

Each check is counted (`gateway_web_bot_auth_total`, by outcome and reason), so
a rise in refused signatures shows on the gateway's metrics.

**Proof:** `SignedAgentFilterTest` — real Ed25519 signatures made in the test:
a trusted one is stamped and the self-declared header dropped; a signature
replayed at another host, one with its window stretched after signing, an
expired, a post-dated and an over-long one, a key nobody trusts, an uncovered
`Signature-Agent`, garbage headers and an unreachable directory are all
anonymous; a client's own `X-Verified-Agent` is stripped; an unconfigured
`Signature-Agent` URL is never fetched; a directory is cached and a flood of
unknown keys causes one fetch; a plain-http directory is refused unless
allowed. `WebBotAuthDraftVectorsTest` — the draft's own published Ed25519
test vectors (dictionary and legacy forms) verify, so the gateway agrees with
the draft's authors and not only with our own signer.

## Honest limits

- Whether the identity server puts an `act` claim into exchanged tokens depends
  on its token-exchange setup; until a mapper adds it, agents read as *declared*.
  That includes the hired digital worker (`hermes-worker`): its badged machine
  token carries no `act` claim, so any agent name it sends is only declared, and
  actions marked `autonomy: medium` (such as `issueCredit`) stay refused to it
  until its identity is proven by the token.
- A program holding a person's token without the header is judged as that
  person (above).
- **A signature says who, not what.** Web Bot Auth signs the host and the
  operator's URL, not the path or the body, so a captured signed request can be
  replayed — to another page on the same host — until it expires (up to 24
  hours). `X-Verified-Agent` therefore means "this traffic carries a valid
  signature from operator X", never "operator X asked for exactly this". There
  is no nonce replay cache yet.
- Only Ed25519 keys are accepted; RSA and ECDSA keys in a directory are skipped.
- The directory's own response signature (draft §5.5.2) is not checked; the
  gateway relies on https to the configured URL. Redirects are not followed.
- Only the first `web-bot-auth` signature on a request is judged; a request
  carrying several is verified on that one alone.
- A request with no `Signature-Agent` header is still checked, against every
  trusted operator's keys by key id. The draft says signers must send it; we
  accept its absence because the key id alone already names one key.
- The host is read from the `Host` header. A proxy in front of the gateway that
  rewrites `Host` breaks every signature; any such proxy must pass `Host` through.
- No operator is configured anywhere yet — not on the laptop fleet, not on the
  box — and there is no browser suite with a signed stand-in agent; the proof is
  the unit tests above. The issue's rate limits and anomaly scoring keyed by
  verified agent are not built.
- The ontology is the only component that reads `X-Verified-Agent`. It trusts it
  because requests reach it through the gateway; a caller already inside the
  fleet's network could set it, as it could set the tenant header.
- Only the ontology reads the actor; other components see the delegated token's
  subject and roles, as before.
