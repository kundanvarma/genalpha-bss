# Bring-your-own CMS/DAM — reference-mode content seam

**Status:** design (not built) · **Author:** —  · **Depends on:** `services/document` (TMF667), existing `ContentStore` seam

An operator who already runs a headless CMS/DAM (Sanity, Contentful, Strapi, Cloudinary,
Bynder, …) should be able to serve product imagery **from their own system**, not the
built-in BSS DAM — with **no per-vendor code in the common case**, and without stale or
broken links. This doc designs that as a first-class *reference* content mode alongside
the existing *hosted* stores.

---

## 1. What we found (grounded in the code)

The content seam already exists and is clean — it is byte-oriented today:

- **`store/ContentStore.java`** — the seam. `put(tenantId, docId, contentType, bytes) → storageKey`
  and `get(tenantId, storageKey) → bytes`. Interface doc already name-checks "Sanity,
  Contentful or S3 … one adapter class."
- **Adapters:** `InRowContentStore` (default, `row:` key, bytes in the `content` column),
  `S3ContentStore` (`s3:` key), `AzureBlobContentStore` (`azure:` key). Selected by
  `@ConditionalOnProperty("bss.content.store", havingValue=…)`.
- **`entity/StoredDocument.java`** — has `content byte[]` **and** `storage_key` (`null` =
  in-row). So a non-byte reference is already representable.
- **`service/DocumentService.java`** — on create: `contentStore.put(...)`; if key starts
  `row:` keeps bytes in-row, else stores the key. `toDto` sets
  `attachmentUrl = href + "/content"`. On read: if `content == null && storageKey != null`,
  `contentStore.get(...)` **loads the bytes and the controller returns them** (BSS proxies).
- **`controller/DocumentController.java`** — `GET /document/{id}/content` returns
  `byte[]` with the stored content-type.
- **Consumers** (catalog offering `attachment[].url`, seed_demo_images, brand logo) only
  ever hold the **URL** `…/document/{id}/content` — they never see the storage backend.

**Key observations:**
1. The catalog already stores a *stable BSS URL*, never the raw backend URL — good, that's
   the indirection we want to preserve.
2. Every hosted store round-trips **bytes through BSS** (`get()` → controller returns them).
   For an external CMS with its own CDN, proxying bytes is the wrong shape — we want to
   hand the browser the CMS's CDN URL.
3. The seam is byte-in/byte-out; a *reference* provider needs a URL-resolution shape.

---

## 2. Best practice we target (researched, not invented)

The consistent guidance for composable/MACH asset integration ([activo-consulting PIM–DAM],
[arroact], [Celum], [WoodWing], [Bynder], [Crystallize]):

- **Linked/reference model** — the commerce/PIM side stores a *reference* to the asset in
  the external DAM; bytes are not copied. Preserves independence, avoids lock-in.
- **Store a stable asset ID, resolve the delivery URL at read time** — baking the public
  URL causes stale/broken links across environments and on re-upload.
- **Deliver via the DAM/CMS CDN** — renditions (thumb/hero), format negotiation, and signed
  URLs are the external system's job, not ours.
- **Webhooks + graceful fallback** — the DAM notifies on create/update/delete; broken/
  unavailable assets must not break the page.
- **One integration contract, many providers** — a clear interface with defined I/O; each
  CMS/DAM is one implementation; a generic HTTP connector covers the long tail.

The counter-view ([Crystallize] "lite DAM") — consolidate assets *into* the commerce
backend — is exactly our **default** (`in-row`/S3/Azure). Reference mode is the *opt-out*
for operators who already run a DAM. We keep the default; we add the opt-out.

---

## 3. The design

### 3a. A sibling seam — `AssetProvider` (reference-oriented)

`ContentStore` stays as-is for hosted bytes. Add a reference seam:

```java
public interface AssetProvider {                     // one per external CMS/DAM, or the generic HTTP one
    /** upload-through-BSS (optional). Returns an opaque asset id. Null-capable: a
     *  pure reference provider (author-in-CMS) may reject uploads. */
    String put(String tenantId, String contentType, byte[] bytes);

    /** resolve the delivery URL for an asset id, at a rendition, optionally signed. */
    ResolvedAsset resolve(String tenantId, String assetId, String rendition);
}
record ResolvedAsset(String url, boolean redirect, Duration ttl) {}
```

Storage key convention: **`ref:<provider>:<assetId>`** (e.g. `ref:sanity:image-abc-1200x800-jpg`).
`StoredDocument.content` stays null; only `storage_key` + `content_type` + metadata persist.

`bss.content.store=external` activates an `ExternalContentStore` that implements the existing
`ContentStore` by delegating to the configured `AssetProvider` — so `DocumentService` needs
**no branching**: `put()` returns `ref:…`, and read is handled by the delivery change (3c).

### 3b. Provider-neutral by default — the generic HTTP connector

`HttpCmsAssetProvider` (`bss.content.provider=http`) covers **any** headless CMS with **zero
code**, configured entirely by properties:

```yaml
bss.content:
  store: external
  provider: http
  http:
    upload-url:      ${CMS_UPLOAD_URL:}        # POST bytes here; empty = reference-only (no upload)
    upload-method:   ${CMS_UPLOAD_METHOD:POST}
    auth-header:     ${CMS_AUTH_HEADER:}       # e.g. "Authorization: Bearer ${CMS_TOKEN}"
    asset-id-json-path: ${CMS_ASSET_ID_PATH:$.document._id}   # pluck id from upload response
    resolve-url-template: ${CMS_RESOLVE_URL:}  # e.g. https://cdn.x.io/{assetId}?w={w}
    signing:         ${CMS_SIGNING:none}       # none | hmac-sha256 | provider
    signing-key:     ${CMS_SIGNING_KEY:}       # secret
```

Named adapters (`SanityAssetProvider`, …) are only written when a provider needs richer
behaviour than templating (GROQ lookups, transform DSL, signed-URL signing). **Sanity ships
as the reference proof adapter; Strapi is the second, open-source option (see 3h).**

### 3h. Provider choices (v1) — one SaaS, one open-source

Deliberately two providers with different shapes, so "any CMS, zero vendor code" is proven,
not asserted:

- **Sanity (SaaS, first)** — common among CSPs; named `SanityAssetProvider` (Assets API +
  `cdn.sanity.io` transforms).
- **Strapi (open-source, second)** — **MIT-licensed** (genuinely free even for a large telco —
  no revenue cap), the most widely adopted OSS headless CMS, runs as a **standalone service**
  with a clean media REST API. Its upload-time responsive formats (`thumbnail/small/medium/
  large`) map onto our `thumb|card|hero|orig` vocab, so no on-the-fly transform engine is
  needed. Strapi's upload API/URL model is **unlike** Sanity's, so proving the **generic HTTP
  connector against real Strapi** is the strongest evidence the vendor-neutral claim holds.

  *Considered and rejected for this slot:* **Directus** — best-in-class on-the-fly URL
  transforms, but **source-available (BSL): free only under ~$5M revenue / <50 staff**, so a
  CSP would owe a commercial fee → fails "free/open-source" for the target buyer (kept as a
  documented source-available runner-up). **Payload** — MIT, but v3 **embeds in a Next.js app**
  rather than running as a standalone CMS the operator points at, plus post-acquisition
  roadmap uncertainty. Strapi's MIT + standalone shape fits this seam best.

  *Self-host note:* Strapi had auth/SQLi CVEs patched in v5.33.2 (May 2026) — a patch-discipline
  item for the operator, not a design blocker.

### 3c. Delivery — redirect, don't proxy

`GET /document/{id}/content[?rendition=hero|thumb|…]`:
- hosted store (in-row/s3/azure) → **unchanged**, returns bytes.
- reference (`ref:…`) → `provider.resolve(...)` → **302** to the resolved CMS/CDN URL
  (rendition mapped to the provider's transform params; signed if configured).

The catalog `attachment.url` **stays `…/document/{id}/content`** — a stable, env-independent
href. Swapping providers, re-uploading, or moving CDNs never rewrites catalog data. This is
the "store ID, resolve at read" best practice, with the stable-href bonus.

*Variant:* an optional `bss.content.direct-url=true` emits the resolved CDN URL straight into
`attachmentUrl` (one less hop, full CDN offload) at the cost of a stale-URL risk — off by
default; the redirect is the safe default.

### 3d. Renditions

`?rendition=<name>` maps a small fixed vocabulary (`thumb`, `card`, `hero`, `orig`) to each
provider's transform params via `resolve-url-template` placeholders (`{w}`,`{h}`,`{fmt}`).
Consumers ask for a rendition; the provider's CDN produces it. Unknown rendition → `orig`.

### 3e. Freshness — webhooks + fallback

- `POST /document/webhook/{provider}` (HMAC-verified) — on asset update/delete, bump a
  per-document `version`/etag so any cached redirect and downstream caches refresh; a delete
  flips the doc to `unavailable`.
- **Graceful fallback:** resolve failure / `unavailable` → 302 to a bundled placeholder
  (never a broken image, never a 500). Logged, not fatal — same fail-open discipline as the
  logistics and OCS seams.

### 3f. Authoring model (who puts the reference in)

Two supported flows, operator's choice:
1. **Upload-through-BSS** — console/copilot upload as today; `ExternalContentStore.put`
   pushes bytes to the CMS via the connector, stores `ref:…`. BSS stays the upload surface.
2. **Author-in-CMS (pull)** — marketing authors in the CMS studio; a reference is recorded
   in BSS by either (a) a console "link existing asset" field (paste/select an asset id), or
   (b) a thin sync/webhook that maps a CMS document → an offering `attachment`. Flow (2b)'s
   mapping is **operator-specific and out of scope for v1** (documented as a gap).

### 3g. Multi-tenancy & secrets (per-tenant from v1 — locked)

- Provider config is **per-tenant from day one** — a `content_provider_config` table keyed by
  `tenant_id` (provider, endpoint/projectId, dataset, rendition template, `direct-url` flag,
  and a **secret-ref**, not the secret itself). Each operator on the shared pool points at
  **their own** Sanity project / CMS; RLS scopes the row like every other tenant-owned table.
  A compose/helm **global default** row (`tenant_id = default`) covers single-tenant and dev.
- Resolution order per request: tenant row → global default → built-in DAM. A tenant with no
  row keeps the hosted DAM — reference mode is strictly opt-in per operator.
- Tokens/signing keys are **secrets** → the table stores a *reference* (env var name / Helm
  secret key); the value is injected from env / Helm secret, **never** the DB, never committed
  (same discipline as PSP keys, `WORKER_AI_API_KEY`). A per-tenant token is a per-tenant
  secret-ref resolved at call time.

---

## 4. Phasing (each phase leaves the tree green) — **Sanity-first**

Reordered per locked decisions: **Sanity is the first real provider**, and **per-tenant config
is in the core**, not a fast-follow. The generic connector is generalised *out of* the Sanity
adapter once it works, so "any CMS" rides on a proven shape rather than a speculative one.

- **P1 — reference core + per-tenant config.** `AssetProvider` interface,
  `ExternalContentStore` (`ref:` key), `content_provider_config` table (tenant-keyed, RLS,
  secret-ref), redirect delivery in the controller, placeholder fallback. Hosted stores
  untouched. Prove: a hand-inserted `ref:sanity:<id>` for tenant A redirects; tenant B with no
  row still serves the hosted DAM.
- **P2 — Sanity adapter (the first provider).** `SanityAssetProvider` — upload to the Assets
  API, resolve `cdn.sanity.io` URLs, renditions via Sanity's transform params (`?w=&h=&fit=&fm=`).
  Per-tenant projectId/dataset/token from the config row + secret-ref. Prove end-to-end against
  a real Sanity project (token via env); dogfood by flipping the demo devices to reference-mode
  Sanity for one tenant.
- **P3 — Sanity webhooks + rendition vocab.** `POST /document/webhook/sanity` (HMAC-verified) →
  version bump + `unavailable` on delete; fixed `thumb|card|hero|orig` vocab mapped to Sanity
  transforms. Prove: an asset update in Sanity busts the cached redirect; a delete → placeholder.
- **P4 — generic HTTP connector, proven on real Strapi (the open-source 2nd option).**
  `HttpCmsAssetProvider` generalised from the Sanity shape: config-driven upload URL /
  auth-header / asset-id JSON-path / resolve-URL template / rendition mapping / signing. Prove
  against a **real self-hosted Strapi** (docker, MIT) whose upload API/URL model is unlike
  Sanity's — a second operator (different tenant) works with **zero vendor code**. Its
  responsive formats map to the `thumb|card|hero|orig` vocab. (A `mock-cms` stub stays as a
  fast/offline fallback for CI.)
- **P5 — suite + docs.** E2E suite (`byo_cms_test.js`): hosted default still serves bytes;
  tenant-A Sanity reference redirects + rendition; webhook invalidation; fallback on broken
  ref; **per-tenant wall** (tenant B never resolves tenant A's provider/assets). Update
  `architecture.md` §content seam + `capability-map`.

Backend-only in P1; P2/P3 touch a real Sanity project; P4 is mock-proven; P5 is the proof.

## 5. Scope boundaries (honest)

- **Not** a headless CMS itself — no page authoring, rich-text, editorial workflow, or
  scheduling. This is *asset reference + delivery*, the DAM half. Marketing-copy/page
  authoring in a CMS pulled into the storefront is a **separate, larger** arc.
- **Flow 2b sync** (CMS-document → offering-attachment mapping) is provider-specific and
  **out of v1** — v1 gives upload-through-BSS and a "link existing asset id" field.
- **Bulk migration** of existing in-row/S3 assets into an external CMS is not in scope
  (operators start reference-mode for new assets; a backfill script is a fast-follow).
- **Signed-URL** support is templated/HMAC in the generic connector; provider-native signing
  (e.g. Cloudinary's) needs its named adapter.

## 6. Estimate

P1–P3 (core + generic connector + renditions/webhooks) ≈ the size of one hardening arc,
backend-only, mock-CMS-proven. P4 (Sanity) small given the seam. P5 one suite.

## 7. Decisions

- **7a. Default delivery = redirect** (stable href, no stale URL) vs `direct-url` (one less
  hop). → *Proposed: redirect default, `direct-url` opt-in.* — **open.**
- **7b. Providers = Sanity (1st, SaaS) + Strapi (2nd, open-source).** ✅ **Locked 2026-08-11.**
  Sanity is common among CSPs → first named adapter (P2). Strapi is the open-source option:
  **MIT** (free even for large telcos), standalone, ubiquitous — proven via the generic HTTP
  connector on **real Strapi** (P4), which also validates "any CMS, zero vendor code."
  Directus rejected for this slot (BSL source-available → not free for CSP-scale); Payload
  rejected (embeds in Next.js, not a standalone CMS). See 3h.
- **7c. Per-tenant provider config.** ✅ **Locked 2026-08-11.** `content_provider_config` is
  tenant-keyed (RLS, secret-ref) **in the core (P1)** — not global-first. Each operator points
  at their own Sanity project; a `default` row covers single-tenant/dev.
- **7d. Rendition vocabulary** — fixed `thumb|card|hero|orig` enough, or expose free-form
  transform params? → *Proposed: fixed vocab v1 (keeps consumers portable across providers).*
  — **open.**

---

*Sources (best practice):* [activo-consulting PIM–DAM] · [arroact headless CMS challenges] ·
[Celum headless DAM] · [WoodWing DAM+CDN] · [Bynder DAM integrations] · [Crystallize DAM vs CMS].

[activo-consulting PIM–DAM]: https://www.activo-consulting.com/dam-knowledge/pim-dam-integration-architectures-apis-governance
[arroact]: https://www.arroact.com/blogs/headless-cms-integration-challenges-forms-dam-commerce/
[arroact headless CMS challenges]: https://www.arroact.com/blogs/headless-cms-integration-challenges-forms-dam-commerce/
[Celum]: https://www.celum.com/en/blog/headless-dam-api-based-asset-management-explained/
[Celum headless DAM]: https://www.celum.com/en/blog/headless-dam-api-based-asset-management-explained/
[WoodWing]: https://www.woodwing.com/blog/how-dam-cdn-work-together-scaled-asset-delivery
[WoodWing DAM+CDN]: https://www.woodwing.com/blog/how-dam-cdn-work-together-scaled-asset-delivery
[Bynder]: https://www.bynder.com/en/blog/top-6-business-apps-for-dam-integration/
[Bynder DAM integrations]: https://www.bynder.com/en/blog/top-6-business-apps-for-dam-integration/
[Crystallize]: https://crystallize.com/answers/ecommerce-essentials/dam-vs-cms
