# Architecture views

Four views of genalpha-bss: what the components are, how tenancy works, how an order becomes
a bill, and how events move. All diagrams are Mermaid and render natively on GitHub.

## 1. Component map (ODA framing)

Channels talk to one gateway; the gateway routes TMF paths to components; components talk to
each other machine-to-machine (client credentials of the acting tenant) and publish domain
events to Kafka. Every component owns its own database.

```mermaid
flowchart TB
    subgraph Channels["Engagement — channels (white-labeled per tenant by hostname)"]
        SHOP["storefront /shop"]
        BIZ["business-console /biz\n(org admin + member my-page)"]
        CSR["csr-console /csr"]
        ADMIN["admin-console /console"]
        APP["mobile-app /app\n(Expo RN: web + iOS/Android)"]
    end

    GW["API Gateway :8080\nHost → tenant (X-Tenant-Id)\nper-channel tenant-config.js"]

    subgraph Party["Party management"]
        PARTY["party-account\nTMF632/666/669"]
        ROLES["user-roles TMF672\n(over the tenant's IdP)"]
    end

    subgraph Core["Core commerce"]
        CAT["product-catalog TMF620"]
        ORD["product-ordering TMF622"]
        INV["product-inventory TMF637"]
        CART["shopping-cart TMF663"]
        QUAL["qualification TMF679"]
        ADDR["geographic-address TMF673"]
        APPT["appointment TMF646"]
        STOCK["product-stock TMF687"]
        AGR["agreement TMF651"]
        PROMO["promotion TMF671"]
        REC["recommendation TMF680"]
        DOC["document TMF667\n(brand + offering content)"]
    end

    subgraph Revenue["Revenue"]
        PAY["payment TMF676"]
        VAULT["payment-method TMF670"]
        BILL["billing TMF678"]
        USAGE["usage TMF635/677"]
    end

    subgraph Production["Production (OSS, thin)"]
        SOM["service-orchestration
TMF641/638/640/685"]
        ASSUR["assurance
TMF642/656"]
    end

    subgraph Care["Customer care"]
        TICKET["trouble-ticket TMF621"]
        INTER["party-interaction TMF683"]
        COMM["communication TMF681"]
        CAMP["campaign (martech)\nevent-triggered journeys"]
        AI["intelligence (AI)\nany-LLM seam + audit"]
    end

    KAFKA[("Kafka\nbss.*.events\ntransactional outbox")]
    IDP[("OIDC IdPs\none issuer per tenant\n(Keycloak realms in dev)")]
    PG[("PostgreSQL\nDB per component\ntenant_id + RLS")]

    SHOP & BIZ & CSR & ADMIN & APP --> GW
    GW --> Party & Core & Revenue & Care

    ORD -.->|"machine calls\n(acting tenant's identity)"| CAT & PARTY & INV & STOCK & PAY & AGR & PROMO
    BILL -.-> INV & CAT & PAY & USAGE & PROMO
    PAY -.-> VAULT
    REC -.-> CAT & INV
    CAMP -.->|"delivers via\nmachine identity"| COMM
    AI -.->|"drafts campaign copy\n(stub/Ollama/OpenAI/Anthropic)"| CAMP
    CSR -.->|"copilot: 360 summary,\nticket reply drafts"| AI
    AI -.->|"churn engine: rules + learned model\n(snapshots -> outcomes -> train)\nChurnRiskDetectedEvent"| KAFKA
    ROLES -.-> IDP

    Core & Revenue & Care -->|events| KAFKA
    KAFKA -->|"tenant-tagged envelopes"| COMM
    KAFKA -->|"ProductOrderCreateEvent"| SOM
    SOM -.->|"completes digital orders
(acting tenant's identity)"| ORD
    Party & Core & Revenue & Care --- PG
```

## 2. Tenancy view (pool model with two locks)

Tenant identity derives from the **verified token issuer** — never from a claim a user could
edit. Anonymous traffic gets its tenant from the hostname at the gateway. Data isolation is
enforced twice: every query carries the tenant predicate in code, and PostgreSQL Row-Level
Security makes even predicate-free SQL tenant-safe.

```mermaid
flowchart LR
    subgraph GenAlpha["Tenant: genalpha"]
        GA_USER["customer/staff\nissuer: /realms/bss"]
        GA_SHOP["localhost → genalpha"]
    end
    subgraph Nova["Tenant: nova"]
        NV_USER["customer/staff\nissuer: /realms/nova"]
        NV_SHOP["*.nova.localhost → nova"]
    end

    GA_USER & NV_USER -->|"JWT (issuer verified\nper-tenant JWKS)"| SVC
    GA_SHOP & NV_SHOP -->|"anonymous + X-Tenant-Id\n(stamped by gateway,\ninbound copies stripped)"| SVC

    SVC["Any component\nTenantScope: issuer ▸ header ▸ default\nMachine calls: acting tenant's client credentials"]

    SVC --> RLS["PostgreSQL\nrestricted role per service\nSET app.tenant_id per connection\nPOLICY: tenant_id = current_setting\n(no tenant ⇒ zero rows; '__system__' for sweepers)"]
```

Cross-tenant access reads as **404, never 403** — foreign ids do not leak existence. The same
pattern stacks three deep: tenant (operator) → org (partner/business unit, via the `org` claim)
→ party (customer).

## 3. Order-to-bill sequence

The storefront journey exercises almost every component. Staff completion is the current
BSS→SOM handoff seam (a thin service-orchestration layer can replace the manual step without
changing anything else).

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer (browser)
    participant GW as gateway
    participant ADDR as address 673
    participant QUAL as qualification 679
    participant CART as cart 663
    participant ORD as ordering 622
    participant STOCK as stock 687
    participant PAY as payment 676
    participant VAULT as vault 670
    participant INV as inventory 637
    participant AGR as agreement 651
    participant PROMO as promotion 671
    participant BILL as billing 678
    participant USE as usage 635/677
    participant COMM as communication 681

    C->>CART: build cart (guest ok, promo code validated anonymously)
    C->>ADDR: validate address → standardized form
    C->>QUAL: serviceability check (gated offerings)
    C->>PAY: authorize one-time charges (card / PSP mock)
    opt save this card
        C->>VAULT: store token + last4 (never the PAN)
    end
    C->>ORD: productOrder (items, promo code, payment ref)
    ORD->>STOCK: reserve devices

    Note over ORD: completion — the SOM auto-completes digital orders<br/>(via ProductOrderCreateEvent) — staff/fulfilment completes physical ones
    ORD->>INV: provision products per item
    ORD->>STOCK: consume reservation
    ORD->>PAY: capture payment
    ORD->>AGR: mint commitment agreements (offering terms)
    ORD->>PROMO: redeem the promo for the owner

    Note over BILL: monthly billing run (staff/scheduled)
    BILL->>INV: active products per owner
    BILL->>USE: rate the period's usage → overage charges
    BILL->>PROMO: redemptions → discount lines
    BILL->>PAY: settle via new card or vaulted method

    ORD--)COMM: events → "Order received / complete"
    BILL--)COMM: events → "Your bill is ready"
    COMM--)C: tenant-scoped inbox notifications
```

## 4. Event backbone

Every write that matters is captured in the same transaction as the business change
(**transactional outbox**), relayed to Kafka, and consumed idempotently. Envelopes carry the
tenant, so downstream consumers stay partitioned without knowing anything about tenancy rules.

```mermaid
flowchart LR
    SVC["component tx:\nbusiness change + outbox row\n(same commit)"] --> RELAY["outbox relay\n(every 2s)"]
    RELAY --> K[("bss.&lt;component&gt;.events\n{eventId, eventTime, eventType,\ntenantId, event{...}}")]
    K --> COMM["communication TMF681\nidempotent per (tenant, eventId)\nacts as the envelope's tenant"]
    K --> CAMP["campaign engine\nmatch trigger -> once per customer\nacts as the envelope's tenant"]
    CAMP -->|"TMF681 send\n(acting tenant's machine identity)"| COMM
    K --> FUT["future consumers:\nanalytics, churn scoring"]
    COMM --> INBOX["customer inbox\n(party- and tenant-scoped)"]
```

Editorially mapped today: order received/completed, bill ready, ticket resolved, installer
booked, cart abandoned ("still thinking it over?"). The campaign engine consumes the same
stream: a campaign is a trigger (event type, optionally a state) plus a message template and
an optional promotion code — matched campaigns reach each customer **exactly once** (a unique
execution row per tenant/campaign/party is the guarantee), delivered as TMF681 messages under
the acting tenant's machine identity.

## Boundary notes

- **The Production seam is now real (thin).** service-orchestration consumes order events,
  decomposes digital orders into TMF641 service orders, mock-activates (TMF640's stand-in),
  records TMF638 services and completes the product order machine-side. Physical/install
  orders still complete on fulfilment. Assurance is live in the same thin spirit: TMF642
  alarm intake (a simulator in dev), critical alarms auto-minting one open TMF656 service
  problem per affected object, resolution clearing the alarms — and agents see open outages
  as a banner across the CSR console.
- **Composability is real**: cross-component calls go through conditional clients with Noop
  fallbacks, channels hide features whose component is absent, and Helm skips disabled modules
  entirely — see the [composer](composer.html).

## 5. Cloud deployment view — proven on both AWS and Azure

The same Helm chart deploys the whole fleet; only the *substrate* differs, and it slots in
behind seams the application never sees. Both stacks ran live (single-node soak scope: core
commerce + billing×2 + the consoles), each proving the P0 tick-locks under two billing
replicas against a **managed** database.

```
        laptop (build) ──push──▶ registry ──pull──▶ managed k8s ──▶ managed Postgres
  AWS:   docker images           ECR                 EKS (Graviton)    RDS (single-AZ)
  Azure: docker images           ACR                 AKS (x86)         Flexible Server
```

What stays identical across clouds: the chart, the images, the in-cluster Kafka + Keycloak,
the seed realms, the smoke (`ops/k8s-soak/smoke.js`), and the security model. What the seams
absorb: the database is `local.postgres.enabled=false` + a managed host; the registry is one
`image.prefix`; the ingress/port-forward story is unchanged.

### Differences observed between the two clouds (live-run truths)

| Dimension | AWS EKS | Azure AKS |
|---|---|---|
| **Cluster access** | EKS module v20 grants the creator NO access by default — needs `enable_cluster_creator_admin_permissions` | AKS grants the creating principal admin automatically |
| **Node architecture** | Graviton (`t4g`, ARM) native + ~30% cheaper — matched the arm64 images directly | Sponsorship tier offered **no ARM sizes at all**; forced x86 (`EC2as_v5`) + cross-building images amd64 with buildx |
| **VM availability** | One instance family, available on ask | Gated **twice** — the offered catalog (`az vm list-skus`) AND per-family vCPU quota (`az vm list-usage`); a size can be listed with zero quota |
| **Region eligibility** | Any region in the account | New subscriptions **refused** westeurope outright; swedencentral had no AKS capacity — landed in northeurope |
| **Registry auth** | `aws ecr get-login-password` per session | Managed-identity `AcrPull` role — nodes pull with no password |
| **Database connections** | RDS `db.t4g.small` ≈ ample for 13 pools of 5 | Flexible Server `B1ms` caps ~50 — the fleet exhausted it; raised `max_connections` + shrank Hikari idle pools to 1 |
| **Postgres extensions** | pg_trgm / vector available on the image | Flexible Server refuses `CREATE EXTENSION` unless allow-listed via `azure.extensions` first |
| **TLS** | RDS accepts plaintext in-VPC | Flexible Server demands TLS by default — turned off for the soak (production keeps it, adds `sslmode=require`) |
| **Database creation** | psql init job over the fleet's `init-databases.sql` | 28 databases as Terraform resources — no init step |
| **Provisioning time** | ~20 min (EKS control plane dominates) | ~8 min cluster, but the sponsorship-tier gauntlet made the *session* longer |

The through-line: **the application code never changed** — every difference lived in Terraform
and two Helm `--set`s. The tenancy model, the RLS second lock, the tick-locks, the outbox, the
GDPR endpoints all behaved identically on both clouds. "Any cloud" is now two invoices, not a
claim. The AKS run also hardened the chart for *any* managed Postgres (bounded idle pools) and
gave the storefront a host-prebuilt image path (`Dockerfile.prebuilt`) for when
vite-under-emulation misbehaves.

## 5. Cloud substrates — the same chart on AWS and Azure, live

Both clouds have now run the fleet for real (2026-07-21, same chart, same
smoke, receipts in [k8s-soak-plan.md](k8s-soak-plan.md)). The mapping:

| Concern | AWS (proven) | Azure (proven) |
|---|---|---|
| Kubernetes | EKS 1.33 | AKS (default channel) |
| Nodes | 2× t4g.large — **Graviton arm64** | 2× EC2as_v5 — **x86 AMD** |
| Database | RDS PostgreSQL 16 | Flexible Server PG16 |
| Registry | ECR (per-repo) | ACR (one registry) |
| DB networking | private subnets, in-VPC only | public endpoint + firewall sentinel |
| Databases created by | `psql` init from a pod | Terraform `for_each` resources |
| Image architecture | arm64 as built on Apple Silicon | **amd64 cross-built** (no ARM SKUs on the subscription tier) |

**Differences that only running taught** — the same chart, two clouds,
opposite answers:

- **Architecture flips per cloud, not per chart**: AWS's fix for
  arm64 images was Graviton nodes; Azure's sponsorship-tier VM catalog
  has NO ARM sizes, so the fix was amd64 images instead (host-built
  artifacts + buildx make that cheap; vite/esbuild refuses to run under
  QEMU, hence the Dockerfile.prebuilt escape hatch).
- **Azure gates VM sizes twice**: an offered SKU (restrictions) can
  still have ZERO family vCPU quota —
