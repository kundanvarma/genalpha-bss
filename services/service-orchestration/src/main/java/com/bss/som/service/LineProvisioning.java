package com.bss.som.service;

import com.bss.som.client.CatalogClient;
import com.bss.som.entity.ResourceAssignment;
import com.bss.som.entity.ResourcePool;
import com.bss.som.entity.ServiceInstance;
import com.bss.som.entity.ServiceRealisation;
import com.bss.som.entity.SimCard;
import com.bss.som.entity.StarterKit;
import com.bss.som.entity.WholesaleAccessOrder;
import com.bss.som.events.DomainEventPublisher;
import com.bss.som.repository.ResourceAssignmentRepository;
import com.bss.som.repository.ResourcePoolRepository;
import com.bss.som.repository.ServiceInstanceRepository;
import com.bss.som.repository.ServiceOrderRepository;
import com.bss.som.repository.ServiceRealisationRepository;
import com.bss.som.repository.SimCardRepository;
import com.bss.som.repository.StarterKitRepository;
import com.bss.som.repository.WholesaleAccessOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.bss.som.mapper.Wire.idOf;

/**
 * The line-level provisioning steps the seam adapters and the orchestrator
 * share — moved out of {@code OrchestrationService} verbatim so that a seam
 * adapter can call them without the orchestrator and the adapters depending
 * on each other. Nothing here decides fulfilment; that is the executor's job.
 */
@Service
public class LineProvisioning {

    private static final Logger log = LoggerFactory.getLogger(LineProvisioning.class);

    private final SimCardRepository sims;
    private final StarterKitRepository starterKits;
    private final com.bss.som.crypto.PukVault pukVault;
    private final ResourceAssignmentRepository assignments;
    private final ResourcePoolRepository pools;
    private final ServiceInstanceRepository services;
    private final ServiceOrderRepository serviceOrders;
    private final CatalogClient catalog;
    private final com.bss.som.client.OcsProvisioningClient ocs;
    private final WholesaleAccessOrderRepository wholesaleOrders;
    private final com.bss.som.client.WholesaleQualificationClient wholesaleQualification;
    private final com.bss.som.client.WholesaleAccessClient wholesaleAccess;
    private final com.bss.som.client.WholesaleRateCardClient wholesaleRateCard;
    private final DomainEventPublisher events;
    private final ServiceRealisationRepository realisations;

    public LineProvisioning(SimCardRepository sims, StarterKitRepository starterKits, com.bss.som.crypto.PukVault pukVault,
            ResourceAssignmentRepository assignments, ResourcePoolRepository pools, ServiceInstanceRepository services,
            ServiceOrderRepository serviceOrders, CatalogClient catalog, com.bss.som.client.OcsProvisioningClient ocs,
            WholesaleAccessOrderRepository wholesaleOrders, com.bss.som.client.WholesaleQualificationClient wholesaleQualification,
            com.bss.som.client.WholesaleAccessClient wholesaleAccess, com.bss.som.client.WholesaleRateCardClient wholesaleRateCard,
            DomainEventPublisher events, ServiceRealisationRepository realisations) {
        this.sims = sims;
        this.starterKits = starterKits;
        this.pukVault = pukVault;
        this.assignments = assignments;
        this.pools = pools;
        this.services = services;
        this.serviceOrders = serviceOrders;
        this.catalog = catalog;
        this.ocs = ocs;
        this.wholesaleOrders = wholesaleOrders;
        this.wholesaleQualification = wholesaleQualification;
        this.wholesaleAccess = wholesaleAccess;
        this.wholesaleRateCard = wholesaleRateCard;
        this.events = events;
        this.realisations = realisations;
    }

    /* ---------------------------------------------------------------- SIM ---- */

    /** ITU E.118-shaped ICCID (89 = telecom, 46 = country) + an 8-digit PUK. */
    public SimCard mintSim(String tenant, String serviceId) {
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder iccid = new StringBuilder("8946");
        for (int i = 0; i < 15; i++) {
            iccid.append(random.nextInt(10));
        }
        SimCard sim = new SimCard();
        sim.setIccid(iccid.toString());
        sim.setTenantId(tenant);
        sim.setServiceId(serviceId);
        sim.setPuk(pukVault.encrypt(
                String.format("%08d", random.nextInt(100_000_000)), sim.getIccid()));
        sim.setCreatedAt(OffsetDateTime.now());
        sim.setLastUpdate(OffsetDateTime.now());
        return sims.save(sim);
    }

    /** A KIT order rides the SIM from the box; anything else mints fresh.
     * The kit was stamped with the product order at activation time. */
    public void attachKitSimOrMint(String tenant, String serviceId, String productOrderId) {
        StarterKit kit = starterKits
                .findFirstByTenantIdAndProductOrderId(tenant, productOrderId).orElse(null);
        if (kit == null) {
            mintSim(tenant, serviceId);
            return;
        }
        SimCard sim = new SimCard();
        sim.setIccid(kit.getIccid());
        sim.setTenantId(tenant);
        sim.setServiceId(serviceId);
        sim.setPuk(kit.getPukCiphertext());
        sim.setCreatedAt(OffsetDateTime.now());
        sim.setLastUpdate(OffsetDateTime.now());
        sims.save(sim);
        log.info("starter kit SIM {} attached to service {} (kit {})",
                kit.getIccid(), serviceId, kit.getActivationCode());
    }

    /** The service's SIM's ICCID, when it has one. */
    public Optional<String> iccidOf(String tenant, String serviceId) {
        return sims.findFirstByTenantIdAndServiceId(tenant, serviceId).map(SimCard::getIccid);
    }

    /* ------------------------------------------------------------ numbers ---- */

    /** The line's number as digits (its MSISDN assignment), or null when it has none yet. */
    public String msisdnOf(String tenant, String serviceId) {
        for (ResourceAssignment a : assignments.findByTenantIdAndServiceId(tenant, serviceId)) {
            String digits = a.getValue() == null ? "" : a.getValue().replaceAll("[^0-9]", "");
            if (digits.length() >= 8) {
                return digits;
            }
        }
        return null;
    }

    /** Keep-your-number: the ported-in number becomes the line's assignment (pool "ported"). */
    public void assignPorted(String tenant, String serviceId, String owner, String portedNumber) {
        ResourceAssignment assignment = new ResourceAssignment();
        assignment.setId(UUID.randomUUID().toString());
        assignment.setTenantId(tenant);
        assignment.setPoolId("ported");
        assignment.setValue(portedNumber);
        assignment.setServiceId(serviceId);
        assignment.setOwnerPartyId(owner);
        assignment.setAssignedAt(OffsetDateTime.now());
        assignments.save(assignment);
    }

    /**
     * Draw the next free value from the tenant's pool of the given type — the
     * shopper's wish wins while it is still FREE; a lost race (two shoppers, one
     * number) falls back to next-free, honestly. Empty when the tenant has no such pool.
     */
    public Optional<String> drawFromPool(String tenant, String poolType, String serviceId, String owner, String wish) {
        return pools.findFirstByTenantIdAndResourceType(tenant, poolType).map(pool -> {
            String value = null;
            if (wish != null && ResourcePool.MSISDN.equals(pool.getResourceType())
                    && assignments.findFirstByTenantIdAndValue(tenant, wish).isEmpty()) {
                value = wish;
            }
            if (value == null) {
                // next FREE — skips any value a wish already consumed from
                // the window ahead, so the counter can never mint a dupe
                long next = pool.getNextValue();
                String candidate = pool.getPrefix() + String.format("%06d", next);
                while (assignments.findFirstByTenantIdAndValue(tenant, candidate).isPresent()) {
                    next++;
                    candidate = pool.getPrefix() + String.format("%06d", next);
                }
                value = candidate;
                pool.setNextValue(next + 1);
                pool.setLastUpdate(OffsetDateTime.now());
                pools.save(pool);
            }
            ResourceAssignment assignment = new ResourceAssignment();
            assignment.setId(UUID.randomUUID().toString());
            assignment.setTenantId(tenant);
            assignment.setPoolId(pool.getId());
            assignment.setValue(value);
            assignment.setServiceId(serviceId);
            assignment.setOwnerPartyId(owner);
            assignment.setAssignedAt(OffsetDateTime.now());
            assignments.save(assignment);
            return value;
        });
    }

    /** The partner's platform owns the account; we hold the code as a "partner" pool assignment. */
    public void assignPartnerCode(String tenant, String serviceId, String owner, String code) {
        ResourceAssignment entitlement = new ResourceAssignment();
        entitlement.setId(UUID.randomUUID().toString());
        entitlement.setTenantId(tenant);
        entitlement.setPoolId("partner");
        entitlement.setValue(code);
        entitlement.setServiceId(serviceId);
        entitlement.setOwnerPartyId(owner);
        entitlement.setAssignedAt(OffsetDateTime.now());
        assignments.save(entitlement);
    }

    /* --------------------------------------------------------------- slice ---- */

    /** Move the line's OCS subscriber to the slice rate plan, remembering the base plan for the way back. */
    public void moveToSliceChargingPlan(String tenant, ServiceInstance line, String sliceChargingSpec) {
        if (sliceChargingSpec == null || sliceChargingSpec.isBlank()) {
            return; // the offer sells priority without a rating change
        }
        if (line.getSliceBaseChargingSpec() == null) {
            // the base plan comes from the line's own offering (spec chargingSpecId)
            String base = serviceOrders.findById(line.getServiceOrderId())
                    .flatMap(so -> catalog.chargingSpecOf(so.getOfferingId())).orElse(null);
            line.setSliceBaseChargingSpec(base == null ? "" : base);
            services.save(line);
        }
        // a line whose plan has no charging footprint has no OCS subscriber yet — the
        // slice plan IS its first charging footprint, so provision rather than move
        if (line.getSliceBaseChargingSpec().isBlank()) {
            ocs.provision(tenant, line.getOwnerPartyId(), line.getId(), sliceChargingSpec);
            return;
        }
        ocs.changeRatePlan(tenant, line.getId(), sliceChargingSpec);
    }

    /* ---------------------------------------------------- wholesale access ---- */

    /**
     * OPEN ACCESS: a broadband component may be delivered over a THIRD-PARTY
     * owner's fibre. If owners serve this address, place the access-seeker order
     * UPSTREAM and record it. Returns the owner's reference when an order was
     * placed AND is active (the retail line is realised over it), the reference
     * with {@code active=false} when placed but pending, and empty when there was
     * nothing to buy (our own network here).
     */
    public Optional<AccessPlacement> placeWholesaleAccess(String tenant, Map<String, Object> item,
            String serviceId, String owner, String productOrderId) {
        String postCode = postCodeOf(item);
        if (postCode == null || postCode.isBlank()) {
            return Optional.empty();
        }
        List<Map<String, Object>> options = wholesaleQualification.accessOptions(postCode, "fiber");
        if (options.isEmpty()) {
            return Optional.empty(); // our own network here — nothing to buy
        }
        int requested = requestedBandwidth(item);
        Map<String, Object> pick = pickAccessOption(options, item, requested);
        if (pick == null) {
            return Optional.empty();
        }
        String accessOwner = String.valueOf(pick.get("accessOwner"));
        String accessLayer = pick.get("accessLayer") == null ? null : String.valueOf(pick.get("accessLayer"));
        Integer bandwidth = pick.get("maxDownMbps") instanceof Number nb ? nb.intValue() : requested;
        String woId = UUID.randomUUID().toString();
        // the async owner callback (Sonata) references OUR order id, so mint it
        // first and hand it over as the buyer reference
        com.bss.som.client.WholesaleAccessClient.AccessOrderResult res =
                wholesaleAccess.order(accessOwner, accessLayer, bandwidth, postCode, serviceId, woId);

        WholesaleAccessOrder wo = new WholesaleAccessOrder();
        wo.setId(woId);
        wo.setTenantId(tenant);
        wo.setProductOrderId(productOrderId);
        wo.setServiceId(serviceId);
        wo.setOrderItemId(idOf(item));
        wo.setOwnerPartyId(owner);
        wo.setAccessOwner(accessOwner);
        wo.setAccessLayer(accessLayer);
        wo.setBandwidthMbps(bandwidth);
        wo.setPostCode(postCode);
        wo.setExternalId(res.externalId());
        wo.setState(res.state());
        wo.setCreatedAt(OffsetDateTime.now());
        if (WholesaleAccessOrder.ACTIVE.equals(res.state())) {
            wo.setActivatedAt(OffsetDateTime.now());
        }
        wo.setLastUpdate(OffsetDateTime.now());
        wholesaleOrders.save(wo);
        // the per-line wholesale rate (fail-soft) rides the event so revenue can
        // book the COGS without its own rate lookup
        com.bss.som.client.WholesaleRateCardClient.Rate rate = wholesaleRateCard.rateCard().get(accessOwner);
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("id", wo.getId());
        event.put("accessOwner", accessOwner);
        event.put("accessLayer", accessLayer == null ? "" : accessLayer);
        event.put("state", wo.getState());
        event.put("productOrderId", productOrderId);
        event.put("serviceId", serviceId);
        event.put("externalId", res.externalId());
        if (rate != null) {
            event.put("ratePerLine", rate.perLine());
            event.put("currency", "EUR");
        }
        events.publish("WholesaleAccessOrderStateChangeEvent", "wholesaleAccessOrder", event);
        log.info("wholesale access {}: {} {} {} Mbit/s for retail line {} (owner ref {})",
                wo.getState(), accessOwner, accessLayer, bandwidth, serviceId, res.externalId());
        return Optional.of(new AccessPlacement(res.externalId(), WholesaleAccessOrder.ACTIVE.equals(wo.getState())));
    }

    /** @param externalId the owner's reference · @param active whether the owner's OSS already activated it */
    public record AccessPlacement(String externalId, boolean active) {
    }

    /** The install/service postcode carried on the component's place. */
    public static String postCodeOf(Map<String, Object> item) {
        if (!(item.get("product") instanceof Map<?, ?> product) || product.get("place") == null) {
            return null;
        }
        Object place = product.get("place");
        if (place instanceof List<?> list && !list.isEmpty()) {
            place = list.get(0);
        }
        return place instanceof Map<?, ?> m && m.get("postCode") != null
                ? String.valueOf(m.get("postCode")).replaceAll("\\s", "") : null;
    }

    /** The retail speed the line is sold at — the downloadSpeed characteristic,
     *  else the biggest number in the offering name, else 1000. */
    static int requestedBandwidth(Map<String, Object> item) {
        if (item.get("product") instanceof Map<?, ?> product
                && product.get("productCharacteristic") instanceof List<?> chars) {
            for (Object c : chars) {
                if (c instanceof Map<?, ?> ch && "downloadSpeed".equals(String.valueOf(ch.get("name")))
                        && ch.get("value") != null) {
                    try {
                        return (int) Double.parseDouble(String.valueOf(ch.get("value")));
                    } catch (NumberFormatException ignored) {
                        // fall through
                    }
                }
            }
        }
        if (item.get("productOffering") instanceof Map<?, ?> off && off.get("name") != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{2,4})")
                    .matcher(String.valueOf(off.get("name")));
            int best = 0;
            while (m.find()) {
                best = Math.max(best, Integer.parseInt(m.group(1)));
            }
            if (best > 0) {
                return best;
            }
        }
        return 1000;
    }

    /**
     * Choose the access owner. If the order names a preferred owner (a productChar
     * accessOwner — the retailer's pick), honour it; otherwise the efficient
     * allocation: the SMALLEST bandwidth tier that still meets the retail speed
     * (headroom without waste), falling back to the biggest available if none reach it.
     */
    static Map<String, Object> pickAccessOption(List<Map<String, Object>> options,
            Map<String, Object> item, int requested) {
        String preferred = null;
        if (item.get("product") instanceof Map<?, ?> product
                && product.get("productCharacteristic") instanceof List<?> chars) {
            for (Object c : chars) {
                if (c instanceof Map<?, ?> ch && "accessOwner".equals(String.valueOf(ch.get("name")))
                        && ch.get("value") != null) {
                    preferred = String.valueOf(ch.get("value"));
                }
            }
        }
        if (preferred != null) {
            for (Map<String, Object> o : options) {
                if (preferred.equalsIgnoreCase(String.valueOf(o.get("accessOwner")))) {
                    return o;
                }
            }
        }
        Map<String, Object> meets = null;
        Map<String, Object> biggest = null;
        for (Map<String, Object> o : options) {
            int bw = o.get("maxDownMbps") instanceof Number n ? n.intValue() : 0;
            if (biggest == null || bw > (Integer) biggest.getOrDefault("maxDownMbps", 0)) {
                biggest = o;
            }
            if (bw >= requested && (meets == null
                    || bw < (Integer) meets.getOrDefault("maxDownMbps", Integer.MAX_VALUE))) {
                meets = o;
            }
        }
        return meets != null ? meets : biggest;
    }

    /* ---------------------------------------------------------- realising ---- */

    /**
     * RECORD a realisation: the orchestrator just exercised a seam for this
     * service. Matched against the RFS list the product spec's CFS declares —
     * matched means the catalog said so; unmatched (rfsId null) means the code
     * did something the catalog never declared. Descriptive: this never changes
     * what was done, and a failure to record never fails the order.
     */
    public void realise(String tenant, String serviceId, String offeringId, String seam, String vendor, String externalRef) {
        try {
            List<CatalogClient.Rfs> declared = catalog.cfsOf(offeringId)
                    .map(c -> catalog.rfsOf(c.id())).orElse(List.of());
            Optional<CatalogClient.Rfs> match = Realisations.declaredFor(declared, seam);
            ServiceRealisation r = new ServiceRealisation();
            r.setId(UUID.randomUUID().toString());
            r.setTenantId(tenant);
            r.setServiceId(serviceId);
            r.setSeam(seam);
            r.setVendor(vendor);
            r.setExternalRef(externalRef == null ? null : externalRef.length() > 160 ? externalRef.substring(0, 160) : externalRef);
            match.ifPresent(m -> {
                r.setRfsId(m.id());
                r.setRfsName(m.name());
                r.setResourceSpecId(m.resourceSpecId());
                r.setResourceSpecName(m.resourceSpecName());
            });
            r.setRealisedAt(OffsetDateTime.now());
            realisations.save(r);
            if (match.isEmpty() && !declared.isEmpty()) {
                log.info("realisation: service {} exercised seam '{}' which its CFS never declared ({} RFS declared)",
                        serviceId, seam, declared.size());
            }
        } catch (RuntimeException e) {
            log.warn("realisation not recorded for service {} seam {}: {}", serviceId, seam, e.getMessage());
        }
    }
}
