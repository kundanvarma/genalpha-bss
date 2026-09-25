# genalpha-bss

A vendor-neutral, multi-tenant telecom BSS on TM Forum ODA: forty components
behind one gateway, seven channels, one catalog that carries the intent every
downstream system needs. This glossary is the project's language; when the
code, a ticket or a page says something else, the glossary wins or gets fixed.

## Catalog to provisioning

**Offering**:
The sellable thing a customer sees and orders: a name, a price and a category, pointing at one product specification. TMF620 `ProductOffering`.
_Avoid_: product (that is what the customer owns after the order), SKU, plan (a plan is one offering family among several)

**Product specification**:
What an offering technically is: its characteristics (the levers a product manager edits) and the customer-facing service it names. TMF620 `ProductSpecification`.
_Avoid_: spec sheet, template, model

**Customer-facing service** (CFS):
The service a product specification promises to the customer, named in its `serviceSpecification[0]`; it declares its fulfilment family. TMF633 `ServiceSpecification` with `serviceType: CFS`.
_Avoid_: service type, component type, category (the category is a shop grouping, not a promise)

**Resource-facing service** (RFS):
One thing a customer-facing service needs the network or a partner to provide, named under the CFS; it declares which product-spec characteristics it consumes and names one resource specification. TMF633 `ServiceSpecification` with `serviceType: RFS`.
_Avoid_: technical service, sub-service, step

**Resource specification**:
The kind of resource a resource-facing service realises, named by its seam; never a vendor. TMF634 `ResourceSpecification`.
_Avoid_: resource type, pool (a pool is inventory of one resource kind), adapter

**Fulfilment family**:
The one word that decides how the orchestrator fulfils a component: `mobile` (a line), `internet` (an install), `tv` (an entitlement), `device` (a shipment), `partner` (an activation on the partner's platform), `security` (a feature toggle). Declared on the CFS; the category string is only the fallback for a spec that names no CFS.
_Avoid_: component type, kind, product type

**Seam**:
A named place where something vendor-specific plugs in — number pool, SIM platform, online charging, network slice, wholesale access, partner entitlement, customer premises equipment — with one adapter per vendor and a stand-in in the fleet. A resource specification names a seam.
_Avoid_: integration, connector, interface (too general), provider (that is the vendor)

**Vendor**:
Who provides a seam for a given tenant, chosen in the tenant configuration (`tenants.yml`), never in the catalog.
_Avoid_: provider, supplier, partner (a partner is a commercial party whose entitlement we activate)

**Realisation**:
The record, on a service in inventory, that one resource-facing service was carried out for it: which RFS, through which seam, by which vendor, with what external reference. Descriptive in step 2; what the orchestrator obeys in step 3.
_Avoid_: fulfilment record, provisioning log, activation (an activation is one seam's act, not the record of it)

## Fulfilment (step 3)

**Seam adapter**:
The one piece of code behind a seam: it says which seam it serves, when it applies (its precondition), which characteristics it can consume, and it runs the vendor's call. One per vendor, registered by seam name; adding a seam is adding one adapter.
_Avoid_: connector, plugin, integration

**Seam registry**:
The orchestrator's map from seam name to seam adapter. The executor asks it, never the family, which code to run.
_Avoid_: dispatcher, router, factory

**Executor**:
The one place the orchestrator decides fulfilment: it walks the declared resource-facing services in a fixed seam order, skips optional ones the product does not call for, and hands each seam adapter the consumed values. It is the only reader of the product specification.
_Avoid_: orchestration plan, workflow, decomposition engine

**Dry run**:
The executor's plan for an offering without any adapter being called: which seams in which order, with which values and which vendor, and which seams no adapter serves here. Shown before launch; a launch-readiness item.
_Avoid_: simulation, preview, test order

**Category fallback**:
What the orchestrator does for a product specification that names no customer-facing service: the old category table decides, and the service records a realisation on seam `category-fallback` so the gate and the ratchet can count it. A debt that may only shrink.
_Avoid_: legacy path, default behaviour

**Billing-only** (family):
A customer-facing service with zero resource-facing services: the product bills and nothing is provisioned. Insurance and top-ups.
_Avoid_: no-service, virtual product

**Compute** (family):
A customer-facing service realised on the `edge-gpu` seam: an inference or compute product that draws a GPU from the edge pool instead of a number from the number pool.
_Avoid_: AI product, GPU plan
