# 0020 — Source-available wording in public; no prospect names in the repo; no report PDFs in git

**Status:** accepted, 2026-09-01 (BUSL-1.1); the repo-hygiene rules 2026-09 (recorded as an ADR 2026-09-22)

## Context

The repository is public and is read by prospects, partners and
competitors. It was Apache-2.0 from the first commit and moved to the
Business Source License on 2026-09-01. Demo tenants are sometimes built
for a named prospect, and the docs produce reports as PDFs that are large,
binary and re-rendered on every edit.

## Decision

- **Licensing wording.** In public the project is described as
  **source-available under BUSL-1.1**, never as "open source": free to
  read, free for all non-production use, free in production under 20 000
  subscribers or €3M service revenue, commercial above that or for offering
  the product itself; every release becomes Apache-2.0 two years after it
  ships. `LICENSING.md` carries the scope map; the e2e suites stay
  Apache-2.0.
- **No prospect names in the repo.** A prospect-specific tenant, seed,
  document or commit message uses a neutral or fictional name. Real
  operator names appear only where the operator is public (the vendor's
  own Taranga tenant) or the material is already public. Country adaptations
  are written as market notes (`docs/markets/`), not as proposals.
- **No report PDFs in git.** `docs/*.pdf` is gitignored; research notes
  and reviews are rendered on demand (`docs/make-note-pdf.mjs`) and handed
  over through Downloads. The three books' PDFs under `docs/book`,
  `docs/manual` and `docs/reference` are the deliberate exception, built by
  their own scripts.
- Secrets never enter git; dev fixtures are named stand-ins behind env
  seams.

## Consequences

- Costs: a PDF must be rebuilt to be shared; a prospect demo needs a cover
  name and the discipline to keep it out of docs and commit subjects; the
  "source-available" phrase costs some goodwill compared with "open source".
- Buys: no confidentiality breach by `git log`; the repository stays small
  and diffable; the licence is stated the same way everywhere, so no reader
  is misled about what they may run.

## Enforced by

`.gitignore` (`docs/*.pdf`); `ops/scan-secrets.sh` as a pre-commit hook;
review of every commit subject, seed and doc for names; the wording in
`README.md` "License" and `LICENSING.md`.

## Related

`LICENSE`, `LICENSING.md`, `README.md` "License", `docs/hardening.md`
"The secret gate", `docs/markets/`.
