# Contextual help — the shelf for the screen you are on

*Help that lives inside every module, limited to that module's context, with the copilot as the last resort rather than the front door.*

## The three layers, cheapest first

1. **The shelf (zero tokens).** Every screen has a "?" that opens the published knowledge articles tagged for that screen. Tags name the place: `pane:<console tab>` (`pane:approvals`, `pane:envelopes`, `pane:productOffering` …), `csr:<desk page>` (`csr:tickets`, `csr:customers` …), `shop:<shop page>` (`shop:bills`, `shop:services` …), `biz:<sales page>`.
2. **Search (near zero).** The same drawer searches all help the reader may see: full-text first, the semantic net only when keywords find nothing. Embeddings are computed at publish time, not per question.
3. **Ask (metered).** Staff with `ai:use` can ask; the answer is written from the retrieved articles only, names its sources, runs at the FAST tier under the tenant's AI budget, and is **cached**: the cache key is the asker's shelf plus the question, the fingerprint is the retrieved articles and their `lastUpdate`, so editing an article invalidates every answer that drew on it without any event plumbing. Shoppers get no Ask: the shelf, search and the Support page.

## The module wall

The tag says *where* an article appears. The **audience** says *who may read it*, and the knowledge service enforces it from the token, never from the request:

| Reader (authority) | Shelves |
|---|---|
| customer (no staff authority) | customer, all |
| CSR (`ticket:write`, `agent`, `wholesale:admin`) | customer, csr, sales, all |
| back office (`catalog:write`, `campaign:write`, `billing:write`, `catalog:approve`) | customer, csr, sales, productOwner, all |
| author (`knowledge:write`) | everything, drafts included |

A customer asking for `tag=pane:approvals` gets an empty list. A CSR never sees "how to author an envelope". A product owner sees the CSR shelf (useful when acting as an agent) but not another tenant's anything.

## Knowledge gaps

When Ask finds no article, the question is recorded per tenant with the screen it came from and a count. The console's Knowledge tab lists them ("asked 7× from pane:approvals") with a **Write it** button that prefills the article. Writing the article moves the question from the metered layer back to the free shelf. Customers cannot read the gap list.

## The BSS explains itself (2026-09-11)

An AI-native BSS should be able to explain its own functionality and how to use it. The Ask layer now does, from three sources it did not have before:

- **The Operator's Manual is in the knowledge base.** `ops/seed/seed_knowledge_manual.py [tenant]` splits `docs/manual/manual.html` into one published article per chapter section (85 today), tagged `manual`, `chapter:<id>` and `pane:<path>` for the console pages the section speaks for (a chapter map plus section-title keywords, e.g. "simulat" → both simulator pages). Idempotent by title; sections that vanish from the manual are removed. Re-run it whenever the manual changes.
- **Ask is page-aware.** With a `context` of `pane:<path>`, retrieval takes the keyword hits that carry the page's tag first, then up to three articles from the page's own shelf, then the remaining keyword hits, then a second keyword pass over the question's content words only ("how do I use the simulator" → "simulator": the knowledge search is AND-shaped, so the full sentence misses an article that never says "use"), then the rest of the shelf. The model is told which screen the person is on and to explain that page from its own articles first. A question whose words matched nothing is still recorded as a knowledge gap even when the shelf could answer.
- **The drawer.** When a page has no article the drawer shows the page's goal line instead of only "no help written". An **Explain this page** button asks, in the page's context, what the page is for and how to use it step by step. A 403 from the AI kill-switch and a 429 from the budget ceiling are said in words, not rendered as "no answer".

Trigger and trace: asking "how to use the simulator" from the Simulator page returned nothing because the page had no article, the sentence matched nothing, Ask had no page context and the manual was not indexed; four pane tags (`journeys`, `campaigns`, `audiences`, `audience-builder`) pointed at paths that do not exist and are fixed. The structural version of this idea — description generated from an operational ontology rather than written prose — is in `docs/ontology-research.md`.

## Seeding

`ops/seed/seed_knowledge_help.py [tenant]` writes the help set for a tenant (idempotent by title): product-owner how-tos for every console tab, CSR desk pages, customer FAQs for every shop page, two sales articles. Brand and currency are substituted. Articles are data: tenants edit them in Catalog & Pricing › Knowledge. `ops/seed/seed_knowledge_manual.py [tenant]` loads the Operator's Manual beside them.

Suite: `ops/e2e/knowledge_help_test.js` (#120).
