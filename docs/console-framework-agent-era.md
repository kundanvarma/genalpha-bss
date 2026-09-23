# Should the vanilla consoles become React — or something else?

*2026-09-23. ADR 0017 (22 September) says channels are React and the vanilla
consoles migrate desk by desk, Offering workspace first. Nothing has migrated
yet. Before spending that effort, the question was put again with A2A, MCP and
generative-engine optimisation in mind. Primary sources fetched directly; the
general web index was exhausted, so adoption figures are not claimed.*

## The short answer

**Do not standardise the consoles on React.** The benefit a framework was going
to buy has already been banked by splitting the files, and two of the four
consoles are too small to repay a rewrite. Keep ADR 0017's desk-by-desk rule but
scope it to the admin console's genuinely interactive desks.

More usefully: **of the three things named, only one bears on this decision at
all**, and it argues for small self-contained desks rather than for React.

| | What it is | Bearing on the console question |
|---|---|---|
| **A2A** | Agent-to-agent messaging, v1.0, contributed by Google to the Linux Foundation, now under the Agentic AI Foundation (Aug 2026) | **None.** It explicitly excludes user interfaces. It is a surface the BSS should *have*, not a reason to change how a screen is built |
| **MCP Apps** | Extension to MCP: a server returns an interactive HTML page rendered in a sandboxed iframe inside an AI client | **The real one** — and it favours one small bundled desk over one large SPA. Framework-agnostic by design |
| **GEO / agentic commerce** | Product feeds, an agentic checkout spec, delegated payment | **None on the console.** It is catalog, ordering and payment work on the *storefront* side |

## 1. A2A does not touch this

A2A's own overview is unambiguous: it standardises communication between agents
across frameworks, and it "explicitly does not include user interfaces,
interactive messaging apps, or internal agent tool-calling mechanisms." MCP
standardises agent-to-tool; A2A standardises agent-to-agent; one agent can speak
both.

So A2A is a question about what the BSS *exposes*, not about what the operator
*sees*. The fleet has no A2A surface today — no agent card, no task endpoint.
That is a real gap if the goal is for a Taranga agent to negotiate with a
partner's agent, and it is worth its own arc. It is not an argument about React.

## 2. MCP Apps is the one that matters, and it cuts the other way

An MCP App is a tool whose description points at a `ui://` resource. The host
fetches that resource — "an HTML page, often bundled with its JavaScript and CSS
for simplicity" — and renders it in a sandboxed iframe, with a content security
policy the resource declares. The app talks back over postMessage in a JSON-RPC
dialect: it can request tool calls, push context, and receive fresh results.
Supported today by Claude, Claude Desktop, VS Code Copilot, Microsoft 365
Copilot, Goose, Postman and others.

Three consequences for this codebase:

1. **The unit is a desk, not an application.** "Show me the offerings that are
   held for approval" wants one screen, self-contained, not a shell that boots a
   router and forty desks. That is the same shape ADR 0017 already chose for
   other reasons.
2. **It is framework-agnostic on purpose.** "Since it's all standard web
   primitives, you can use any framework or none at all." The official starter
   templates are React, Vue, Svelte, Preact, Solid **and vanilla JavaScript**.
   Nothing about this direction requires React; the bundle being small matters
   more than which library made it.
3. **The sandbox and the declared CSP are the security model**, and the fleet
   has just done that work at the gateway. A desk published as an MCP App
   inherits the same discipline.

The BSS is unusually well placed for this and mostly is not using it. The
ontology already serves governed actions over MCP `tools/list` and `tools/call`
with an approval ladder and a receipt — but `resources/list` returns an empty
list, so there is no `ui://` anything. The hard part (a governed, typed,
audited action surface) is built; the cheap part (a view over it) is not.

## 3. GEO is a storefront concern, and the gates already exist

Agentic commerce, as OpenAI documents it, asks a merchant for product feeds
(API or file), an agentic checkout implementation, and delegated payment through
a PSP. Every one of those is catalog, ordering and payment work. None of it is
back-office UI.

The per-tenant gates are already modelled — `agent-commerce: off | discovery |
full` and `ai-visibility: dark | search-only | open`, with a newborn operator
dark by default. What is missing is the feed and the checkout conformance, not a
console rewrite.

## 4. What a framework would actually buy, measured

The earlier research ([engineering-principles-agent-era.md](engineering-principles-agent-era.md) §3)
found no study measuring React against vanilla DOM for agent maintenance. What
is measured is file size and context fill, typed props, and a compile step.

The file-size half is done. The admin console went from one 8,483-line `app.js`
to 52 files, every one under the 300-line ceiling, held by the ratchet. That was
the finding's actual content, and it has been collected without changing
framework.

What is still unbought: typed props and a compile step that catches a mistake
before a Playwright suite does. That is real, and it is worth having **on a desk
where the interaction is complex enough to get wrong** — the Offering workspace,
the journey canvas, the copilot proposal card. It is not worth having on a
two-file dealer console that lists a kit shelf.

### The sizes, since they decide the small cases

| Front end | Payload | Shape |
|---|---|---|
| storefront (React) | 424 KB, one bundle | whole app |
| csr-console (React) | 312 KB, one bundle | whole app |
| admin-console | 518 KB across 52 files | one desk is a few KB |
| business-console | 42 KB across 3 files | |
| dealer-console | 12 KB across 2 files | |
| partner-console | 10 KB across 2 files | |

A React runtime is roughly 45 KB gzipped before any application code. Rewriting
the partner console in React multiplies its payload by an order of magnitude to
render four tables. If those desks are ever published as MCP Apps — fetched per
invocation into a sandbox — that cost is paid every time.

## 5. Recommendation

**Per console:**

- **admin-console** — continue ADR 0017, but narrow it. React islands for the
  desks with real interaction complexity, starting with the Offering workspace
  as already agreed. Leave the list-and-drawer desks in place; they are
  configuration over a shared renderer and a rewrite buys nothing.
- **business-console** — leave. 42 KB over three files, and its `app.js` is at
  the ratchet ceiling, which is the constraint that matters.
- **dealer-console**, **partner-console** — leave, and say so in the ADR so the
  question stops being reopened. Two files each, no interaction complexity, and
  the escaping work plus ratchet rule 5 closed the defect that actually existed.

**On framework, if a desk is published as an MCP App:** build that app as its own
small bundle rather than shipping the console's React tree into a sandbox, and
prefer a light runtime (Preact and Solid are both official templates) where the
desk is simple. The desk and the MCP App are two views over the same governed
ontology action — the logic belongs in neither.

**What deserves the effort more than a rewrite**, in order:

1. **`ui://` resources on the ontology's MCP server.** The governed actions are
   already there with an approval ladder and receipts; a handful of desks
   rendered as MCP Apps would put the BSS inside the tools operators already
   use, and is a days-not-weeks job.
2. **An A2A surface** — an agent card and a task endpoint over the same
   registered actions, so another operator's agent can transact with this one
   under the same governance. This is the genuine gap of the three.
3. **Agentic-commerce feeds and checkout** on the storefront side, where the
   per-tenant gates already exist and the conformance does not.

## Honest limits

- The general web index was exhausted, so this rests on primary documentation
  fetched directly. **No adoption figures are claimed** for A2A, MCP Apps or
  agentic commerce; "supported by" lists are the vendors' own.
- No study measures React against vanilla for agent-maintained code. The
  recommendation rests on measured file-granularity effects plus the payload
  arithmetic above, not on a controlled comparison that does not exist.
- MCP Apps is an *extension* to MCP, not core, and host support varies by
  client. Building desks against it is a bet on a young surface — a cheap one
  at a few desks, an expensive one if the console is rebuilt around it.
- I have not prototyped a desk as an MCP App. The claim that it is days-not-weeks
  comes from the protocol shape and the fact that the actions already exist, not
  from having done it.

## Sources

[A2A Protocol overview](https://a2a-protocol.org/latest/) ·
[Model Context Protocol introduction](https://modelcontextprotocol.io/docs/getting-started/intro) ·
[MCP Apps overview](https://modelcontextprotocol.io/extensions/apps/overview) ·
[OpenAI agentic commerce documentation](https://developers.openai.com/commerce/) ·
[engineering-principles-agent-era.md](engineering-principles-agent-era.md) ·
[ADR 0017](adr/0017-channels-react-small-components-ratchet.md)
