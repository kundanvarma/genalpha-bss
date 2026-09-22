# Do the classic engineering principles still apply when an agent writes the code?

*2026-09-22. Prompted by a developer's feedback after the "Honest Machine"
talk: the internals are stringly typed (`Map<String, Object>` everywhere),
the back office is one hand-built vanilla-JS file, and the build instructions
carried no guidance on internal architecture. The question behind the
feedback: do separation of concerns, a type system, a UI framework and low
boilerplate still pay off when code can be regenerated and run in ten minutes?
Three research passes, sources below, strength graded honestly.*

## The short answer

The principles survive, but for a different reason than the one they were
taught with. They used to serve the human reader. Now they serve the agent's
context budget, its search, and the deterministic check it can run. Two of
them matter **more** than before, one matters **less**, and one has changed
shape.

| Principle | Verdict | Mechanism in the agent era |
|---|---|---|
| Typed boundaries and a compiler in the loop | matters **more** | in typed languages ~94 % of what breaks in generated code is a type error; the checker is the cheapest, widest oracle an agent can run, and it works before a test exists |
| Small cohesive modules, separation of concerns | matters **more** | accuracy degrades with context fill; resolve rates fall off a cliff past ~10 files touched; an 8 000-line file is a documented failure mode for edit tools |
| Explicit contracts, tests the agent can run | unchanged, now the primary control | every vendor's guidance converges on "give the agent a check it can run"; benchmarks without interface specs degrade sharply |
| Enforced architecture (rules in tools, not prose) | **new**, non-negotiable | agents preserve architecture 4–36 % of the time when nothing enforces it; correctness-passing edits silently erode structure; conventions files help *navigation*, not design |
| A UI framework over hand-built DOM | matters, **indirectly** | no study measures React vs vanilla; what is measured is file granularity, typed props and a compile step, which a framework gives you and one big file does not |
| Low boilerplate, human readability | matters **less** | ceremony is free to generate; the cost moved to review load and tokens, so boilerplate is neutral unless it bloats context |
| "Regenerate, don't maintain" | legitimate only for disposable code | at scale, agent code needs more corrective maintenance and its burden tracks the no-review rate; a spec cannot be rebuilt bit-identically, so every regeneration is a regression surface |

## What the evidence says, strand by strand

### 1. Types and the compiler

- In TypeScript, only ~6 % of LLM compile failures are syntactic; **94 % are
  type errors**. Enforcing types during generation cut compile errors by
  52–75 % and lifted repair of non-compiling code by 37 % (Mündler et al.,
  PLDI 2025). Compiler diagnostics fed back fix ~74 % of real Rust errors
  (RustAssistant, 2023). Type definitions were the single most valuable
  context in an LSP-in-the-loop study (Blinn et al., OOPSLA 2024).
- Two independent repository benchmarks put **Java and Rust at the top and
  JavaScript at the bottom** (SWE-bench Multilingual 2025; Multi-SWE-bench
  2025). Confounded by training-corpus frequency and repo quality; nobody has
  run the controlled typed-vs-untyped agent experiment.
- **The hedge:** an incomplete type constrainer can cut correctness by up to
  97 % (Biagiola et al., 2026); gains shrink as models get better at a
  language (1.2× on TypeScript vs 4× on a niche language); and practitioners
  who chose Go over Rust did so because loop speed and a simple type system
  beat a rich one the agent cannot drive (Ronacher, 2025).
- **Stringly-typed internals specifically:** no study isolates
  `Map<String, Object>` against typed models. The inference is logical, not
  measured: a map hides exactly the signal the 94 % finding says the checker
  would catch. Structured outputs prove schema conformance at the boundary
  only.

### 2. Maintainability and churn

- **Telemetry** (GitClear 2024–2026, 623 M changed lines): refactoring fell
  from 25 % to 3.8 % of changed lines, block duplication +81 %, new code wires
  into existing code 35 % less. DORA 2024/2025: AI adoption raised throughput
  but lowered stability; "AI is an amplifier" of the system it lands in.
  Uplevel: +41 % bug rate with Copilot. Vendor attribution is inferred, no
  controls; treat as direction, not size.
- **The only RCT** (METR, July 2025): experienced maintainers of large mature
  repos were **19 % slower** with AI, mostly because of tacit quality
  standards the agent could not see. Pre-2025 tools.
- **Longitudinal**: agent-authored code needs significantly more *corrective*
  maintenance, and each +10 pp in a project's no-review rate adds ~6 % to that
  burden (Xia & Miller, 2026). But vendor variance dominates: one agent's PRs
  were reverted half as often as humans' (Kraishan, 2026). "Agent code" is not
  one population.
- **Long-horizon agents**: resolve rate falls to ~10 % at 10+ files touched
  and degrades "significantly" without human-written interface specs
  (SWE-Bench Pro, 2025); accuracy degrades with input length even on trivial
  tasks (Context Rot, 2025); retrieval gains matter 9× more on 1 000-file
  repos (Cursor, 2025).
- **Agents do not keep architecture on their own**: maintainability-preserving
  edits succeed 36 % on average, dependency control 4 %, and 13 % of edits
  pass the tests while failing the structural oracle (Needle in the Repo,
  2026). Conventions files move correctness by ≤10–15 pp and mainly improve
  *file localisation* (Khatri 2026; Shepard & Albrecht 2026).
- **The counter-position, taken seriously**: spec-driven development (Kiro,
  GitHub Spec Kit, Thoughtworks Radar at *Assess*) and "forget the code
  exists" (Karpathy). Fowler's structural objection stands: a prompt is
  non-deterministic, so a spec cannot be a source of truth the way source
  is. Throwaway code is legitimate for prototypes (Willison, Osmani). The
  postmortems that exist (Lovable's 170 exposed apps, the Replit deleted
  database) are review failures, not regeneration successes.

### 3. UI frameworks and open-JSON typing

- **No study measures React versus vanilla DOM for agent maintenance.** What
  is measured: file size and context fill degrade accuracy (Anthropic, Aider),
  and repeated edits to a huge single file with inline scripts corrupt it
  silently (Claude Code issues #52054, #55894, 2026). Vendors that build UI
  agents standardise on React + TypeScript for training density and tunable
  autofix, not from a controlled comparison. The htmx author keeps a
  3 500-line single file deliberately and names the price: no types, no
  modules.
- **TM Forum JSON**: the only inspectable Java implementation (FIWARE)
  generates models from the OAS and validates extensions at the boundary; TMF
  v5 ships `@type` discriminator mappings for exactly this; nobody types
  `Characteristic.value` beyond an open value. The serious shape is **records
  per known type, an `extensions` map for the unknown, an anti-corruption
  layer between TMF DTOs and domain records** ("parse, don't validate").

## What this means for this codebase

The developer is right on all three counts, and the evidence changes the
*order* and *shape* of the remedy, not its direction.

1. **Rules go into tools, not prose.** A conventions file improves navigation,
   not design. The guardrail that works is a check the agent runs: an
   architecture test that fails the build when a service-package method
   returns `Map<String, Object>`, with today's 605 as a baseline that may only
   fall; typecheck and lint hooks after every edit.
2. **Type the core, keep the edge open.** TMF payloads stay open JSON at the
   wire; behind a mapper, domain records with an extensions map. Start with
   the components the demos lean on (catalog, ordering, ontology), suite-
   guarded, so every step is proven the same way the features were.
3. **Granularity before framework.** Split the 8 483-line back office into
   modules per desk first; most of the agent benefit arrives there. Then
   strangle into components desk by desk, starting with the Offering
   workspace already agreed.
4. **Tests stay the primary control; types are the cheaper, earlier one.**
   The 146 suites are the spec the agent can run. Types catch the class of
   error the suites only find later, and they catch it before a test exists.
5. **Say it in public.** The code is proven by its suites, not by its type
   system. That is the next hardening arc, and it belongs on the honest-limits
   slide.

## Sources

Types: Mündler et al., *Type-Constrained Code Generation*, PLDI 2025,
arxiv.org/abs/2504.09246 · Biagiola et al., 2026, arxiv.org/abs/2606.21619 ·
Agrawal et al., *Monitor-Guided Decoding*, NeurIPS 2023,
arxiv.org/abs/2306.10763 · Blinn et al., *Typed Holes*, OOPSLA 2024,
arxiv.org/abs/2409.00921 · SWE-bench Multilingual, swebench.com/multilingual ·
Multi-SWE-bench, arxiv.org/abs/2504.02605 · RustAssistant,
arxiv.org/abs/2308.05177 · De-Hallucinator, arxiv.org/abs/2401.01701 ·
Ronacher, *Agentic Coding Recommendations*, Jun 2025 · Beck, *Augmented
Coding*, Jun 2025 · Anthropic, Claude Code best practices; Cursor rules docs.

Maintainability: GitClear reports 2024/2025/2026 · DORA 2024/2025 · METR RCT,
Jul 2025, arxiv.org/abs/2507.09089 · Stack Overflow survey 2025 · Uplevel
2024 · Veracode 2025 · Sonar 2025 · Xia & Miller 2026, arxiv.org/abs/2607.09902
· Kraishan 2026, arxiv.org/abs/2609.17598 · SWE-Bench Pro,
arxiv.org/abs/2509.16941 · Chroma, *Context Rot*, Jul 2025 · Cursor semantic
search, Nov 2025 · *Needle in the Repo*, arxiv.org/abs/2603.27745 · Khatri
2026, arxiv.org/abs/2607.27250 · Shepard & Albrecht 2026,
arxiv.org/abs/2606.20512 · Böckeler/Fowler, *The nature of abstraction*, Jun
2025 · Willison, Mar 2025 · Osmani, Aug 2025 · Thoughtworks Radar v34 ·
Semafor on Lovable, May 2025 · Fortune on Replit, Jul 2025.

UI and TMF: Web-Bench, arxiv.org/abs/2505.07473 · WebGen-Bench,
arxiv.org/abs/2505.03733 · Vercel v0 composite models, Jun 2025 · Aider
unified diffs and repo map · Claude Code issues #52054, #55894 · htmx essays
2023/2026 · Fowler, *Strangler Fig* · FIWARE tmforum-api · TMF620 v5 OAS ·
Jackson polymorphic deserialisation · King, *Parse, don't validate*.

*Method note: the searches ran without a general web index (budget
exhausted), so primary sources were fetched directly. Vendor practice at
Totogi, Netcracker and Salesforce could not be inspected and is a gap.*
