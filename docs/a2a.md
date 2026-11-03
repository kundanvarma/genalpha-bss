# The A2A door — another company's agent hands the BSS a task (#426)

*2026-11-03. Agent2Agent (A2A) 1.0 — the protocol now kept by the Linux
Foundation's Agentic AI Foundation alongside MCP — lets one company's agent
delegate a task to another company's agent and get back a result with an id.
The ontology now answers it. MCP is for an agent that wants our **tools**;
A2A is for an agent that wants to hand us a **task** and read the outcome.
Both sit on the same registry of governed actions, and neither adds a right.*

## What a partner's agent can do

A partner — a streaming service, a comparison site, a household's own
assistant — reads the operator's **Agent Card**, signs the person in at the
operator's identity server, and sends a task.

| Skill | What it does | Changes anything? |
|---|---|---|
| **Can this household pick this benefit?** (`check-pick-benefit`) | A dry run of *pick a benefit*: is the benefit on offer, may this person order for this household, do the business rules agree. Every condition comes back with its verdict and, when it fails, the condition's own words. | No. Nothing is spent and no receipt is written — the task says so. |
| **Explain a governed action** (`explain-action`) | What an action of this operator means, what it needs, who may do it and what follows, from the registry itself. | No. |
| **Pick a benefit for a household** (`pick-benefit`) | Picks the benefit with one of the household's Choices, as the signed-in person. Every condition is checked again first. | Yes — and only where the operator has switched it on (below). Writes a decision receipt; the task carries its id. |

The card lists exactly these skills, read off the same list the door
enforces, so the card cannot advertise a skill the door would refuse. Picking
is **off by default**: an operator turns it on with
`ONTOLOGY_A2A_EXECUTE_ENABLED=true` (`ontology.a2a.execute-enabled`). Until
then a partner can ask, and only ask.

## How it finds us and signs in

- **The card** is at `/.well-known/agent-card.json` on the operator's own
  hostname (the gateway routes it to the ontology). It is public by the
  protocol's design: an agent reads it *before* it has a token. It holds the
  endpoint, the skills and where to sign in — no one's data. It is declared in
  `ops/security/public-paths.txt` like every other anonymous path.
- **Which operator** is decided by the hostname, exactly as for the shop: the
  gateway stamps the tenant, never the request. One operator's card names its
  own identity server; a partner of one operator is never sent to another's login.
  The card is named after the operator's brand (`brand-name` in `tenants.yml`,
  e.g. "Taranga — governed actions"), never after the software underneath; an
  operator with no brand gets the plain "Governed actions".
- **Signing in** is OAuth 2.0 authorization code at that operator's realm (the
  card names the authorization, token and discovery URLs). The partner's agent
  then calls the endpoint with the **person's** access token. That is the only
  key: no token, the door answers 401.
- **The endpoint** is `POST /ontology/v1/a2a`, JSON-RPC 2.0, with the header
  `A2A-Version: 1.0`. Methods: `SendMessage` (the task), `GetTask` (read it
  back), `CancelTask` (answers "cannot be cancelled" — every task here is
  already finished when it is returned).

A task is a message with a data part naming the skill and its inputs:

```json
{"jsonrpc":"2.0","id":1,"method":"SendMessage","params":{"message":{
  "messageId":"m-1","role":"ROLE_USER","parts":[
    {"data":{"skill":"check-pick-benefit","inputs":{"customerId":"…","benefitOfferingId":"…"}}}]}}}
```

The answer is a task: its id, its state, a sentence for the person, the full
result as a data part, and in `metadata` the governed action, whether it was a
dry run, and — when something was done — the **decision id** an auditor finds
in the decision log.

| State | When |
|---|---|
| `TASK_STATE_COMPLETED` | The dry run answered (yes or no — a "no" is a complete answer), the explanation was given, or the pick was placed. |
| `TASK_STATE_INPUT_REQUIRED` | A required input is missing; the message names it in the action's words. |
| `TASK_STATE_REJECTED` | A skill this operator does not offer, or a pick the rules refused (the condition is named, and the refusal is in the receipt store). |
| `TASK_STATE_WORKING` | A pick that needs a person's approval was filed for one. |
| `TASK_STATE_FAILED` | The pick could not be attempted, or its outcome is uncertain until it is reconciled. |

## What it cannot do

- **Nothing outside the registry.** Every skill goes through the same check and
  execute services as the console, the SDK and MCP: preconditions, the
  person's permission, the policy domain, the autonomy gate, the approval
  ladder and the receipts. The door adds no rights and removes none.
- **Act as anyone but the person whose token it carries**, or in any operator
  but the one that issued that token. A `tenant` in the request that disagrees
  with the token is refused; a task id is readable only by the person who
  delegated it (anyone else is told it does not exist).
- **Hide who it is.** The receipt names the agent: verified when the token's
  `act` claim names it (a token exchange), declared when only its
  `X-GenAlpha-Agent` header does, and `external-a2a` — a registered agent —
  when it says nothing ([agent-identity.md](agent-identity.md)).

## Signed cards

When the deployment supplies a key — `ONTOLOGY_A2A_SIGNING_KEY` (PEM PKCS#8;
EC P-256 signs ES256, RSA signs RS256) and `ONTOLOGY_A2A_SIGNING_KEY_ID` — the
card carries a detached JWS over its RFC 8785 canonical form with
`signatures` left out (A2A §8.4). The key comes from the environment, never
from the repo; a configured key that cannot be read stops the service at
start rather than serving unsigned cards quietly. With no key the card is
served unsigned and says nothing about a signature.

**Proof:** `A2aServerTest` (MockMvc): the card's endpoint, issuer per tenant
and exact skill list; with picking off, the card and the door offer only the
dry run and the explanation; the signature verifies and a tampered card does
not; a dry run completes through the check service naming the agent; a pick
carries the receipt's decision id and `GetTask` returns it to its owner only;
a refusal is a rejected task; an unknown skill is rejected with nothing
checked; a missing input asks for it; no token is 401; the version and the
tenant are enforced. `GatewayRoutingTest` pins the card's route.

## Honest limits

- **No browser suite yet.** The issue's proof — a stand-in partner agent
  delegating through the gateway with a real token — is not built; the MockMvc
  test mocks the check and execute services (their own tests prove the
  governance). Until the suite exists, this is proven at the component, not end to end.
- **Tasks live in memory.** `GetTask` reads a bounded in-process map (2,000
  tasks); a restart, or a second replica, forgets them and the id reads as not
  found. What was *done* is durable — the receipt store and the decision log —
  but the task wrapper is not.
- **Synchronous only.** No streaming (`SendStreamingMessage`,
  `SubscribeToTask`), no push notifications, no `ListTasks`, no extended card;
  each is answered with the protocol's "unsupported" error. `returnImmediately`
  and `historyLength` are ignored and no history is kept. A pick filed for
  approval stays `WORKING` here: this door does not follow the approval desk.
- **A2A 1.0 only, JSON-RPC only.** A request without `A2A-Version: 1.0` is read
  as 0.3 (as the spec says) and refused with `VersionNotSupportedError`, as are
  the 0.3 method names (`message/send`, `tasks/get`). The gRPC and HTTP+JSON
  bindings are not served.
- **The card's sign-in URLs assume Keycloak.** The authorization and token URLs
  are the realm issuer plus Keycloak's paths, and the metadata URL is the
  issuer's OpenID discovery document (a superset of RFC 8414 metadata). A tenant
  on another identity server would need these read from its registry entry.
  No scopes are named: the realm grants roles, which the token carries.
- **The signing key's public half is not published.** The card has a `kid` but
  no `jku`, so a partner verifies with a key the operator gives it out of band.
  Key rotation is one key at a time.
- **The token is not audience-bound to this endpoint** (as for MCP): any valid
  token of the tenant is accepted, and the person's rights are the limit.
- **Three skills.** Every other governed action is reachable through MCP, not
  here; widening A2A is a deliberate per-action decision, not a default.
