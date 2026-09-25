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
