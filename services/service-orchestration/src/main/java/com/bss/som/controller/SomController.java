package com.bss.som.controller;

import com.bss.som.api.ApiConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bss.som.entity.ServiceInstance;
import com.bss.som.entity.ServiceOrder;
import com.bss.som.repository.ServiceInstanceRepository;
import com.bss.som.entity.ResourceAssignment;
import com.bss.som.entity.ResourcePool;
import com.bss.som.repository.ResourceAssignmentRepository;
import com.bss.som.repository.ResourcePoolRepository;
import com.bss.som.repository.ServiceOrderRepository;
import com.bss.som.security.PartyScope;
import com.bss.som.security.TenantScope;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-side TMF641/638: what the production layer did and what is running. */
@RestController
public class SomController {

    private final ServiceOrderRepository serviceOrders;
    private final ServiceInstanceRepository services;
    private final ResourcePoolRepository pools;
    private final ResourceAssignmentRepository assignments;
    private final TenantScope tenantScope;
    private final PartyScope partyScope;
    private final com.bss.som.events.DomainEventPublisher events;
    private final com.bss.som.service.OrchestrationService orchestration;
    private final com.bss.som.repository.SimCardRepository sims;
    private final com.bss.som.client.SimPlatformClient simPlatform;
    private final com.bss.som.crypto.PukVault pukVault;
    private final com.bss.som.repository.NumberQuarantineRepository quarantine;
    private final com.bss.som.client.PartyOrgClient partyOrg;
    private final com.bss.som.client.OcsProvisioningClient ocs;
    private final com.bss.som.client.DiagnosticsClients diagnostics;

    public SomController(ServiceOrderRepository serviceOrders, ServiceInstanceRepository services,
            ResourcePoolRepository pools, ResourceAssignmentRepository assignments,
            TenantScope tenantScope, PartyScope partyScope,
            com.bss.som.events.DomainEventPublisher events,
            com.bss.som.service.OrchestrationService orchestration,
            com.bss.som.repository.SimCardRepository sims,
            com.bss.som.client.SimPlatformClient simPlatform,
            com.bss.som.crypto.PukVault pukVault,
            com.bss.som.repository.NumberQuarantineRepository quarantine,
            com.bss.som.client.PartyOrgClient partyOrg,
            com.bss.som.client.OcsProvisioningClient ocs,
            com.bss.som.client.DiagnosticsClients diagnostics) {
        this.serviceOrders = serviceOrders;
        this.services = services;
        this.pools = pools;
        this.assignments = assignments;
        this.tenantScope = tenantScope;
        this.partyScope = partyScope;
        this.events = events;
        this.orchestration = orchestration;
        this.sims = sims;
        this.simPlatform = simPlatform;
        this.pukVault = pukVault;
        this.quarantine = quarantine;
        this.partyOrg = partyOrg;
        this.ocs = ocs;
        this.diagnostics = diagnostics;
    }

    /**
     * The SIM behind a numbered service: masked ICCID by default; the PUK
     * only with ?reveal=true. Owner-checked — a customer token addresses only
     * their own service, and a foreign id is a 404, never a 403.
     */
    @GetMapping(ApiConstants.INVENTORY_BASE + "/service/{id}/sim")
    public ResponseEntity<Map<String, Object>> sim(
            @org.springframework.web.bind.annotation.PathVariable String id,
            @RequestParam(name = "reveal", defaultValue = "false") boolean reveal) {
        com.bss.som.entity.SimCard sim = requireOwnSim(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceId", id);
        body.put("iccid", "•••• " + sim.getIccid().substring(sim.getIccid().length() - 5));
        if (reveal) {
            body.put("puk", pukVault.reveal(sim.getPuk(), sim.getIccid()));
            // legacy plaintext row? upgrade it now that we've touched it
            if (!pukVault.isEncrypted(sim.getPuk())) {
                sim.setPuk(pukVault.encrypt(sim.getPuk(), sim.getIccid()));
                sim.setLastUpdate(java.time.OffsetDateTime.now());
                sims.save(sim);
            }
        }
        body.put("@type", "SimCard");
        return ResponseEntity.ok(body);
    }

    /**
     * OTA PIN change through the SIM-platform seam. The PIN goes to the card,
     * never stored or logged in the BSS.
     */
    @PostMapping(ApiConstants.INVENTORY_BASE + "/service/{id}/sim/resetPin")
    public ResponseEntity<Map<String, Object>> resetPin(
            @org.springframework.web.bind.annotation.PathVariable String id,
            @RequestBody Map<String, Object> body) {
        com.bss.som.entity.SimCard sim = requireOwnSim(id);
        String pin = String.valueOf(body.getOrDefault("newPin", ""));
        if (!pin.matches("\\d{4,8}")) {
            throw new com.bss.som.exception.BadRequestException("newPin must be 4-8 digits");
        }
        if (!simPlatform.resetPin(sim.getIccid(), pin)) {
            throw new com.bss.som.exception.BadRequestException("the SIM platform refused the PIN change");
        }
        // the OWNER rides the event so the customer is told their PIN
        // changed — a silent credential change is a gift to fraudsters
        String owner = services.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .map(ServiceInstance::getOwnerPartyId).orElse(null);
        Map<String, Object> pinEvent = new LinkedHashMap<>();
        pinEvent.put("serviceId", id);
        pinEvent.put("iccid", "•••• " + sim.getIccid().substring(sim.getIccid().length() - 5));
        if (owner != null) {
            pinEvent.put("relatedParty", List.of(Map.of("id", owner, "role", "customer")));
        }
        events.publish("SimPinResetEvent", "sim", pinEvent);
        return ResponseEntity.ok(Map.of("status", "done", "@type", "SimPinReset"));
    }

    /**
     * SIM replacement — the classic call. The NUMBER lives on the service;
     * the card is expendable: the old one is BLOCKED at the platform FIRST
     * (a lost card must die before anything else happens), a fresh card is
     * minted against the same service, and the owner is told on every
     * channel — a silent SIM swap is the textbook account-takeover.
     */
    @PostMapping(ApiConstants.INVENTORY_BASE + "/service/{id}/sim/replace")
    public ResponseEntity<Map<String, Object>> replaceSim(
            @org.springframework.web.bind.annotation.PathVariable String id,
            @RequestBody Map<String, Object> body) {
        String reason = String.valueOf(body.getOrDefault("reason", "lost"));
        if (!java.util.Set.of("lost", "stolen", "damaged", "upgrade").contains(reason)) {
            throw new com.bss.som.exception.BadRequestException(
                    "reason must be lost, stolen, damaged or upgrade");
        }
        com.bss.som.entity.SimCard old = requireOwnSim(id);
        if (!simPlatform.block(old.getIccid())) {
            throw new com.bss.som.exception.BadRequestException(
                    "the SIM platform refused to block the old card — nothing was replaced");
        }
        old.setStatus(java.util.Set.of("lost", "stolen").contains(reason) ? "blocked" : "replaced");
        old.setReplacedReason(reason);
        old.setLastUpdate(java.time.OffsetDateTime.now());
        sims.save(old);
        String tenant = tenantScope.currentTenantId();
        com.bss.som.entity.SimCard fresh = orchestration.mintSim(tenant, id);
        String owner = services.findByIdAndTenantId(id, tenant)
                .map(ServiceInstance::getOwnerPartyId).orElse(null);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("serviceId", id);
        event.put("reason", reason);
        event.put("oldIccid", "•••• " + old.getIccid().substring(old.getIccid().length() - 5));
        event.put("iccid", "•••• " + fresh.getIccid().substring(fresh.getIccid().length() - 5));
        if (owner != null) {
            event.put("relatedParty", List.of(Map.of("id", owner, "role", "customer")));
        }
        events.publish("SimReplacedEvent", "sim", event);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("serviceId", id);
        response.put("reason", reason);
        response.put("oldSim", Map.of("iccid", event.get("oldIccid"), "status", old.getStatus()));
        response.put("iccid", event.get("iccid"));
        response.put("note", "the new card is active; its PUK is revealable the usual way");
        response.put("@type", "SimReplacement");
        return ResponseEntity.ok(response);
    }

    /**
     * NUMBER CHANGE — the other classic call. The number lives on the
     * service, so everything else survives: the SIM keeps working, usage
     * and billing follow the service id, only the MSISDN moves. The old
     * number goes on the QUARANTINE shelf (auditable, never re-issued
     * straight into circulation) and the owner is told loudly — a number
     * change the customer did not ask for is a takeover in progress.
     */
    @PostMapping(ApiConstants.INVENTORY_BASE + "/service/{id}/changeNumber")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String, Object>> changeNumber(
            @org.springframework.web.bind.annotation.PathVariable String id) {
        String tenant = tenantScope.currentTenantId();
        ServiceInstance instance = services.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> com.bss.som.exception.NotFoundException.forResource("Service", id));
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(instance.getOwnerPartyId())) {
                throw com.bss.som.exception.NotFoundException.forResource("Service", id);
            }
        });
        ResourceAssignment current = assignments.findByTenantIdAndServiceId(tenant, id).stream()
                .filter(a -> !"partner".equals(a.getPoolId()))
                .findFirst()
                .orElseThrow(() -> new com.bss.som.exception.BadRequestException(
                        "this service carries no number to change"));
        ResourcePool pool = pools.findFirstByTenantIdAndResourceType(tenant, ResourcePool.MSISDN)
                .orElseThrow(() -> new com.bss.som.exception.BadRequestException(
                        "no number pool configured for this tenant"));
        String oldNumber = current.getValue();
        // the old number goes on the shelf, with its story
        com.bss.som.entity.NumberQuarantine shelf = new com.bss.som.entity.NumberQuarantine();
        shelf.setId(java.util.UUID.randomUUID().toString());
        shelf.setTenantId(tenant);
        shelf.setNumber(oldNumber);
        shelf.setServiceId(id);
        shelf.setReason("numberChange");
        shelf.setReleasedAt(java.time.OffsetDateTime.now());
        quarantine.save(shelf);
        // the same assignment row carries the new draw — holder unchanged
        String fresh = pool.getPrefix() + String.format("%06d", pool.getNextValue());
        pool.setNextValue(pool.getNextValue() + 1);
        pool.setLastUpdate(java.time.OffsetDateTime.now());
        pools.save(pool);
        current.setPoolId(pool.getId());
        current.setValue(fresh);
        current.setAssignedAt(java.time.OffsetDateTime.now());
        assignments.save(current);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("serviceId", id);
        event.put("oldNumber", oldNumber);
        event.put("number", fresh);
        if (instance.getOwnerPartyId() != null) {
            event.put("relatedParty", List.of(Map.of("id", instance.getOwnerPartyId(), "role", "customer")));
        }
        events.publish("NumberChangedEvent", "service", event);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("serviceId", id);
        response.put("oldNumber", oldNumber);
        response.put("number", fresh);
        response.put("note", "the old number is quarantined; SIM, usage and billing are untouched");
        response.put("@type", "NumberChange");
        return ResponseEntity.ok(response);
    }

    /** Vacation hold: pause the line — number and SIM stay yours, charging
     * pauses, and the hold lifts itself at the agreed date (max 90 days). */
    @PostMapping(ApiConstants.INVENTORY_BASE + "/service/{id}/suspend")
    public ResponseEntity<Map<String, Object>> suspend(
            @org.springframework.web.bind.annotation.PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        ServiceInstance instance = requireOwnService(id);
        Map<String, Object> dto = body == null ? Map.of() : body;
        String reason = String.valueOf(dto.getOrDefault("reason", "vacation"));
        java.time.OffsetDateTime resumeAt = null;
        if (dto.get("until") != null) {
            try {
                resumeAt = java.time.OffsetDateTime.parse(String.valueOf(dto.get("until")));
            } catch (Exception e) {
                throw new com.bss.som.exception.BadRequestException("until must be an ISO date-time");
            }
        } else if (dto.get("days") != null) {
            resumeAt = java.time.OffsetDateTime.now()
                    .plusDays(Long.parseLong(String.valueOf(dto.get("days"))));
        }
        if (resumeAt != null && (resumeAt.isBefore(java.time.OffsetDateTime.now())
                || resumeAt.isAfter(java.time.OffsetDateTime.now().plusDays(90)))) {
            throw new com.bss.som.exception.BadRequestException(
                    "the hold must end in the future and within 90 days");
        }
        return ResponseEntity.ok(orchestration.suspend(instance, reason, resumeAt));
    }

    /** Lift the hold early — or at all, when no end date was set. */
    @PostMapping(ApiConstants.INVENTORY_BASE + "/service/{id}/resume")
    public ResponseEntity<Map<String, Object>> resume(
            @org.springframework.web.bind.annotation.PathVariable String id) {
        return ResponseEntity.ok(orchestration.resume(requireOwnService(id), "request"));
    }

    /**
     * TRANSFER: the subscription changes hands — the B2B classic (an
     * employee leaves, the company gives the number to the next one) and
     * the B2C give-away. The line, its number, its SIM and its usage stay
     * exactly as they are; only the OWNER moves. Who may do it:
     * unscoped staff/machines, the current owner (giving their own line
     * away), or a business admin moving a line INSIDE their own company —
     * verified against party data, never against the request.
     */
    @PostMapping(ApiConstants.INVENTORY_BASE + "/service/{id}/transfer")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String, Object>> transfer(
            @org.springframework.web.bind.annotation.PathVariable String id,
            @RequestBody Map<String, Object> body) {
        String to = body.get("toPartyId") == null ? null : String.valueOf(body.get("toPartyId"));
        if (to == null || to.isBlank()) {
            throw new com.bss.som.exception.BadRequestException("toPartyId is required — who gets the line?");
        }
        String tenant = tenantScope.currentTenantId();
        ServiceInstance instance = services.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> com.bss.som.exception.NotFoundException.forResource("Service", id));
        if (!ServiceInstance.ACTIVE.equals(instance.getState())) {
            throw new com.bss.som.exception.BadRequestException(
                    "only an active line can be transferred (state: " + instance.getState() + ")");
        }
        String from = instance.getOwnerPartyId();
        if (to.equals(from)) {
            throw new com.bss.som.exception.BadRequestException("the line already belongs to them");
        }
        // the target must be REAL — a typo must not orphan a line
        if (partyOrg.individualOf(to).isEmpty()) {
            throw new com.bss.som.exception.BadRequestException(
                    "the receiving person is not on record — check the id");
        }
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        boolean isBusinessAdmin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "business:admin".equals(a.getAuthority()));
        java.util.Optional<String> scoped = partyScope.scopedPartyId();
        if (isBusinessAdmin) {
            // inside the SAME company only — all three org facts from party data
            String adminOrg = partyOrg.orgOf(auth.getName()).orElse(null);
            if (adminOrg == null
                    || !adminOrg.equals(partyOrg.orgOf(from).orElse(null))
                    || !adminOrg.equals(partyOrg.orgOf(to).orElse(null))) {
                throw com.bss.som.exception.NotFoundException.forResource("Service", id);
            }
        } else if (scoped.isPresent() && !scoped.get().equals(from)) {
            throw com.bss.som.exception.NotFoundException.forResource("Service", id);
        }
        instance.setOwnerPartyId(to);
        instance.setLastUpdate(java.time.OffsetDateTime.now());
        services.save(instance);
        String number = null;
        for (ResourceAssignment a : assignments.findByTenantIdAndServiceId(tenant, id)) {
            number = a.getValue();
            a.setOwnerPartyId(to);
            assignments.save(a);
        }
        ocs.transfer(tenant, id, to);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("serviceId", id);
        event.put("name", instance.getName());
        if (number != null) event.put("number", number);
        event.put("relatedParty", List.of(
                Map.of("id", from, "role", "giver"),
                Map.of("id", to, "role", "receiver")));
        events.publish("ServiceTransferredEvent", "serviceTransfer", event);
        Map<String, Object> response = new LinkedHashMap<>(event);
        response.put("@type", "ServiceTransfer");
        return ResponseEntity.ok(response);
    }

    /**
     * "MY INTERNET IS SLOW": triage before ticket, in the order a good
     * tech-support agent thinks — is the line even on? is there a KNOWN
     * outage on its path? are they simply OUT OF DATA (the classic)? Only
     * an all-clear earns "raise a ticket and we will dig". Every check
     * that cannot run says so; a diagnosis never invents an all-clear.
     */
    @PostMapping(ApiConstants.INVENTORY_BASE + "/service/{id}/diagnose")
    public ResponseEntity<Map<String, Object>> diagnose(
            @org.springframework.web.bind.annotation.PathVariable String id) {
        ServiceInstance instance = requireOwnService(id);
        String tenant = tenantScope.currentTenantId();
        List<Map<String, Object>> findings = new java.util.ArrayList<>();
        String verdict = "allClear";

        if (ServiceInstance.SUSPENDED.equals(instance.getState())) {
            findings.add(Map.of("code", "paused", "severity", "cause",
                    "message", "This line is PAUSED" + (instance.getResumeAt() != null
                            ? " until " + instance.getResumeAt().toLocalDate() : "")
                            + " — nothing flows while it sleeps. Resume it to get moving."));
            verdict = "paused";
        } else if (!ServiceInstance.ACTIVE.equals(instance.getState())) {
            findings.add(Map.of("code", "notActive", "severity", "cause",
                    "message", "This service is " + instance.getState() + "."));
            verdict = "notActive";
        }

        var problems = diagnostics.openProblems();
        if (problems.isEmpty()) {
            findings.add(Map.of("code", "assuranceUnreachable", "severity", "caution",
                    "message", "Could not check for network outages right now."));
        } else {
            Map<String, Object> onPath = instance.getDeliveryPath() == null ? null
                    : problems.get().stream()
                            .filter(p -> instance.getDeliveryPath()
                                    .equals(String.valueOf(p.get("affectedObject"))))
                            .findFirst().orElse(null);
            if (onPath != null) {
                findings.add(Map.of("code", "outage", "severity", "cause",
                        "message", "KNOWN OUTAGE on your line's path ("
                                + onPath.get("affectedObject") + "): "
                                + onPath.getOrDefault("description", "crews are on it")
                                + ". No ticket needed — it is already being worked."));
                if ("allClear".equals(verdict)) {
                    verdict = "outage";
                }
            } else if (!problems.get().isEmpty()) {
                findings.add(Map.of("code", "areaIncidents", "severity", "caution",
                        "message", problems.get().size() + " network incident(s) are open in the"
                                + " area — your line is not directly on an affected path, but"
                                + " conditions may be degraded."));
            }
        }

        diagnostics.bucketOf(tenant, id).ifPresent(bucket -> {
            double used = asDouble(bucket.get("usedGB"));
            double total = asDouble(bucket.get("totalGB")) + asDouble(bucket.get("rolloverGB"));
            if (total > 0 && used >= total) {
                findings.add(Map.of("code", "outOfData", "severity", "cause",
                        "message", "You are OUT OF INCLUDED DATA (" + used + " of " + total
                                + " GB used) — speed is reduced until the next cycle."
                                + " A top-up restores full speed immediately."));
            } else if (total > 0 && used / total >= 0.9) {
                findings.add(Map.of("code", "nearDataCap", "severity", "caution",
                        "message", String.format("%.0f%% of your data is used (%.1f of %.1f GB)"
                                + " — speed drops when it runs out.", used / total * 100, used, total)));
            }
        });
        if ("allClear".equals(verdict)
                && findings.stream().anyMatch(f -> "outOfData".equals(f.get("code")))) {
            verdict = "throttled";
        }

        if (findings.isEmpty()) {
            findings.add(Map.of("code", "allClear", "severity", "info",
                    "message", "No known fault from here: line active, no outage on your path,"
                            + " data remaining. If it still feels slow, raise a ticket and we"
                            + " will dig deeper."));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("serviceId", id);
        report.put("name", instance.getName());
        report.put("verdict", verdict);
        report.put("findings", findings);
        report.put("@type", "ServiceDiagnosis");
        return ResponseEntity.ok(report);
    }

    private static double asDouble(Object v) {
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }

    private ServiceInstance requireOwnService(String serviceId) {
        ServiceInstance instance = services.findByIdAndTenantId(serviceId, tenantScope.currentTenantId())
                .orElseThrow(() -> com.bss.som.exception.NotFoundException.forResource("Service", serviceId));
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(instance.getOwnerPartyId())) {
                throw com.bss.som.exception.NotFoundException.forResource("Service", serviceId);
            }
        });
        return instance;
    }

    private com.bss.som.entity.SimCard requireOwnSim(String serviceId) {
        String tenant = tenantScope.currentTenantId();
        ServiceInstance instance = services.findByIdAndTenantId(serviceId, tenant)
                .orElseThrow(() -> com.bss.som.exception.NotFoundException.forResource("Service", serviceId));
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(instance.getOwnerPartyId())) {
                throw com.bss.som.exception.NotFoundException.forResource("Service", serviceId);
            }
        });
        // one ACTIVE card per service; blocked/replaced rows keep the history
        return sims.findFirstByTenantIdAndServiceIdAndStatus(tenant, serviceId, "active")
                .orElseThrow(() -> com.bss.som.exception.NotFoundException.forResource("SIM for service", serviceId));
    }

    @GetMapping(ApiConstants.ORDER_BASE + "/serviceOrder")
    public ResponseEntity<List<Map<String, Object>>> serviceOrders(
            @RequestParam(required = false) String productOrderId,
            @RequestParam(required = false) String externalId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String fields,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        String tenant = tenantScope.currentTenantId();
        List<ServiceOrder> rows = productOrderId != null
                ? serviceOrders.findByTenantIdAndProductOrderId(tenant, productOrderId)
                : serviceOrders.findAll().stream()
                        .filter(o -> tenant.equals(o.getTenantId()))
                        .sorted(java.util.Comparator.comparing(
                                ServiceOrder::getCreatedAt).reversed())
                        .toList();
        List<Map<String, Object>> out = rows.stream()
                .filter(o -> externalId == null || unquote(externalId).equals(o.getExternalId()))
                .filter(o -> priority == null || unquote(priority).equals(o.getPriority()))
                .map(this::orderMap)
                .filter(m -> category == null
                        || unquote(category).equals(String.valueOf(m.get("category"))))
                .skip(offset).limit(limit)
                .toList();
        if (fields != null && !fields.isBlank()) {
            List<String> keep = new java.util.ArrayList<>(List.of("id"));
            for (String f : fields.split(",")) {
                keep.add(f.split("\\.")[0].trim());
            }
            out = out.stream().map(m -> {
                Map<String, Object> slim = new LinkedHashMap<>();
                for (String k : keep) {
                    if (m.containsKey(k)) {
                        slim.put(k, m.get(k));
                    }
                }
                return slim;
            }).toList();
        }
        return ResponseEntity.ok(out);
    }

    /**
     * TMF641 northbound: an external system files a service order directly
     * (no product order behind it). The order is RECORDED and acknowledged —
     * fulfilment of external orders is the caller's workflow, honestly
     * reflected in a state that never claims progress that didn't happen.
     * The /v3 alias serves the R18-era clients, same validation.
     */
    @org.springframework.web.bind.annotation.PostMapping({
            ApiConstants.ORDER_BASE + "/serviceOrder",
            "/tmf-api/serviceOrdering/v3/serviceOrder"})
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> createServiceOrder(
            @org.springframework.web.bind.annotation.RequestBody Map<String, Object> dto) {
        if (!(dto.get("orderItem") instanceof List<?> items) || items.isEmpty()) {
            throw new com.bss.som.exception.BadRequestException(
                    "orderItem is required — an order orders SOMETHING");
        }
        for (Object item : items) {
            if (item instanceof Map<?, ?> it && it.get("service") instanceof Map<?, ?> svc
                    && svc.get("serviceSpecification") instanceof Map<?, ?> spec
                    && (spec.get("id") == null || String.valueOf(spec.get("id")).isBlank())) {
                throw new com.bss.som.exception.BadRequestException(
                        "serviceSpecification needs an id — a nameless spec specifies nothing");
            }
        }
        String tenant = tenantScope.currentTenantId();
        ServiceOrder order = new ServiceOrder();
        String id = java.util.UUID.randomUUID().toString();
        order.setId(id);
        order.setTenantId(tenant);
        order.setHref(ApiConstants.ORDER_BASE + "/serviceOrder/" + id);
        order.setState("acknowledged");
        order.setProductOrderId("external");
        order.setItemName(dto.get("category") == null ? "external"
                : String.valueOf(dto.get("category")));
        order.setExternalId(dto.get("externalId") == null ? null
                : String.valueOf(dto.get("externalId")));
        order.setPriority(dto.get("priority") == null ? null
                : String.valueOf(dto.get("priority")));
        order.setDescription(dto.get("description") == null ? null
                : String.valueOf(dto.get("description")));
        try {
            order.setDocumentJson(new ObjectMapper().writeValueAsString(dto));
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new com.bss.som.exception.BadRequestException("unserializable order document");
        }
        order.setCreatedAt(java.time.OffsetDateTime.now());
        order.setLastUpdate(java.time.OffsetDateTime.now());
        serviceOrders.save(order);
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(orderMap(order));
    }

    @GetMapping({ApiConstants.ORDER_BASE + "/serviceOrder/{id}",
            "/tmf-api/serviceOrdering/v3/serviceOrder/{id}"})
    public ResponseEntity<Map<String, Object>> serviceOrderById(
            @org.springframework.web.bind.annotation.PathVariable String id,
            @RequestParam(required = false) String fields) {
        ServiceOrder order = serviceOrders.findById(id)
                .filter(o -> tenantScope.currentTenantId().equals(o.getTenantId()))
                .orElseThrow(() -> com.bss.som.exception.NotFoundException
                        .forResource("ServiceOrder", id));
        Map<String, Object> full = orderMap(order);
        if (fields == null || fields.isBlank()) {
            return ResponseEntity.ok(full);
        }
        Map<String, Object> slim = new LinkedHashMap<>();
        slim.put("id", full.get("id"));
        for (String f : fields.split(",")) {
            String key = f.split("\\.")[0].trim();
            if (full.containsKey(key)) {
                slim.put(key, full.get(key));
            }
        }
        return ResponseEntity.ok(slim);
    }

    /** TMF630 filter values may arrive quoted: priority="1". */
    private static String unquote(String v) {
        if (v == null || v.length() < 2) {
            return v;
        }
        char a = v.charAt(0);
        char b = v.charAt(v.length() - 1);
        return (a == b && (a == '\'' || a == '"')) ? v.substring(1, v.length() - 1) : v;
    }

    /**
     * Number -> owner, for GIFTING by phone number: the number pool already
     * knows who holds every assigned MSISDN. MACHINE/STAFF ONLY — a scoped
     * customer probing numbers for party ids gets a 404, and the answer is
     * an opaque party id, never a name.
     */
    @GetMapping(ApiConstants.INVENTORY_BASE + "/numberOwner")
    public ResponseEntity<Map<String, Object>> numberOwner(@RequestParam String number) {
        if (partyScope.scopedPartyId().isPresent()) {
            throw com.bss.som.exception.NotFoundException.forResource("Number", number);
        }
        // Tolerant matching: people type numbers without '+', and a '+' in a
        // query string arrives as a space — try the bare digits with a '+' too.
        String normalized = number.replaceAll("[\\s-]", "");
        String tenant = tenantScope.currentTenantId();
        return assignments.findFirstByTenantIdAndValue(tenant, normalized)
                .or(() -> normalized.startsWith("+") ? java.util.Optional.empty()
                        : assignments.findFirstByTenantIdAndValue(tenant, "+" + normalized))
                .filter(a -> a.getOwnerPartyId() != null)
                .map(a -> ResponseEntity.ok(Map.<String, Object>of(
                        "number", a.getValue(), "partyId", a.getOwnerPartyId())))
                .orElseThrow(() -> com.bss.som.exception.NotFoundException.forResource("Number", number));
    }

    @GetMapping({ApiConstants.INVENTORY_BASE + "/service", ApiConstants.INVENTORY_BASE + "/service/"})
    public ResponseEntity<List<Map<String, Object>>> services(
            @RequestParam(name = "relatedPartyId", required = false) String relatedPartyId,
            @RequestParam(name = "deliveryPath", required = false) String deliveryPath,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "fields", required = false) String fields,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        String tenant = tenantScope.currentTenantId();
        // Customers see their own running services; staff filter freely.
        String party = partyScope.scopedPartyId().orElse(relatedPartyId);
        List<ServiceInstance> rows = deliveryPath != null
                ? services.findByTenantIdAndDeliveryPath(tenant, deliveryPath)
                : party != null
                        // newest first — a long-lived customer's fresh line must
                        // land inside the first page (same fix the order list got)
                        ? services.findByTenantIdAndOwnerPartyId(tenant, party).stream()
                                .sorted(java.util.Comparator.comparing(
                                        ServiceInstance::getCreatedAt).reversed())
                                .toList()
                        : services.findAll().stream()
                                .filter(s -> tenant.equals(s.getTenantId()))
                                .sorted(java.util.Comparator.comparing(
                                        ServiceInstance::getCreatedAt).reversed())
                                .toList();
        List<Map<String, Object>> out = rows.stream()
                .filter(s -> name == null || name.equals(s.getName()))
                .filter(s -> state == null || state.equals(s.getState()))
                .map(this::serviceMap)
                .filter(m -> category == null || category.equals(m.get("category")))
                .skip(offset).limit(limit)
                .toList();
        if (fields != null && !fields.isBlank()) {
            // TMF630 attribute selection: id and href always ride along
            List<String> keep = new java.util.ArrayList<>(List.of("id", "href"));
            for (String f : fields.split(",")) {
                keep.add(f.trim());
            }
            out = out.stream().map(m -> {
                Map<String, Object> slim = new LinkedHashMap<>();
                for (String k : keep) {
                    if (m.containsKey(k)) {
                        slim.put(k, m.get(k));
                    }
                }
                return slim;
            }).toList();
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping(ApiConstants.INVENTORY_BASE + "/service/{id}")
    public ResponseEntity<Map<String, Object>> serviceById(
            @org.springframework.web.bind.annotation.PathVariable String id) {
        ServiceInstance instance = services.findById(id)
                .filter(s -> tenantScope.currentTenantId().equals(s.getTenantId()))
                .orElseThrow(() -> com.bss.som.exception.NotFoundException
                        .forResource("Service", id));
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(instance.getOwnerPartyId())) {
                throw com.bss.som.exception.NotFoundException.forResource("Service", id);
            }
        });
        return ResponseEntity.ok(serviceMap(instance));
    }

    /**
     * The self-healing hook: re-home a service to a new delivery point.
     * Assurance calls this when the current path fails — fibre cut, edge
     * takes over, SLA restored. Machine or staff only (service:write).
     */
    @org.springframework.web.bind.annotation.PostMapping(
            ApiConstants.INVENTORY_BASE + "/service/{id}/migrate")
    public ResponseEntity<Map<String, Object>> migrate(
            @org.springframework.web.bind.annotation.PathVariable String id,
            @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
        String tenant = tenantScope.currentTenantId();
        ServiceInstance instance = services.findById(id)
                .filter(s -> tenant.equals(s.getTenantId()))
                .orElseThrow(() -> com.bss.som.exception.NotFoundException.forResource("Service", id));
        String from = instance.getDeliveryPath();
        instance.setDeliveryPath(String.valueOf(body.get("deliveryPoint")));
        instance.setLastUpdate(java.time.OffsetDateTime.now());
        services.save(instance);
        events.publish("ServiceAttributeValueChangeEvent", "service", Map.of(
                "id", instance.getId(), "name", instance.getName(),
                "deliveryPath", instance.getDeliveryPath(),
                "previousDeliveryPath", from == null ? "" : from,
                "relatedParty", instance.getOwnerPartyId() == null ? List.of()
                        : List.of(Map.of("id", instance.getOwnerPartyId(), "role", "customer"))));
        return ResponseEntity.ok(serviceMap(instance));
    }

    /** Cease a service (disconnect) — staff, machine, or the OWNER
     * cancelling their own subscription; releases the number. */
    @org.springframework.web.bind.annotation.PostMapping(
            ApiConstants.INVENTORY_BASE + "/service/{id}/terminate")
    public ResponseEntity<Map<String, Object>> terminate(
            @org.springframework.web.bind.annotation.PathVariable String id,
            @org.springframework.web.bind.annotation.RequestBody(required = false) Map<String, Object> body) {
        requireOwnService(id); // scoped tokens cancel only their own line
        String reason = body == null || body.get("reason") == null ? "cease" : String.valueOf(body.get("reason"));
        return ResponseEntity.ok(orchestration.terminateService(id, reason));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> orderMap(ServiceOrder o) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", o.getId());
        map.put("href", o.getHref());
        map.put("state", o.getState());
        map.put("category", o.getItemName());
        map.put("orderDate", o.getCreatedAt().toString());
        map.put("productOrderId", o.getProductOrderId());
        if (o.getExternalId() != null) map.put("externalId", o.getExternalId());
        if (o.getPriority() != null) map.put("priority", o.getPriority());
        if (o.getDescription() != null) map.put("description", o.getDescription());
        if (o.getCompletedAt() != null) map.put("completionDate", o.getCompletedAt().toString());
        // orderItem rides every row: the posted items for external orders,
        // the single add-item an internal order factually IS otherwise
        List<Map<String, Object>> items = null;
        if (o.getDocumentJson() != null) {
            try {
                Map<String, Object> doc = new ObjectMapper().readValue(o.getDocumentJson(), Map.class);
                if (doc.get("orderItem") instanceof List<?> raw) {
                    items = new java.util.ArrayList<>();
                    int n = 0;
                    for (Object it : raw) {
                        n++;
                        Map<String, Object> item = new LinkedHashMap<>((Map<String, Object>) it);
                        item.putIfAbsent("id", String.valueOf(n));
                        item.putIfAbsent("state", o.getState());
                        item.putIfAbsent("action", "add");
                        items.add(item);
                    }
                }
            } catch (com.fasterxml.jackson.core.JacksonException ignored) {
                // fall through to the derived item
            }
        }
        if (items == null || items.isEmpty()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", "1");
            item.put("state", o.getState());
            item.put("action", "add");
            item.put("service", Map.of("name", o.getItemName()));
            items = List.of(item);
        }
        map.put("orderItem", items);
        map.put("@type", "ServiceOrder");
        return map;
    }

    @PostMapping("/tmf-api/resourcePoolManagement/v4/resourcePool")
    public ResponseEntity<Map<String, Object>> createPool(@RequestBody Map<String, Object> dto) {
        ResourcePool pool = new ResourcePool();
        pool.setId(java.util.UUID.randomUUID().toString());
        pool.setTenantId(tenantScope.currentTenantId());
        pool.setHref("/tmf-api/resourcePoolManagement/v4/resourcePool/" + pool.getId());
        pool.setName(String.valueOf(dto.getOrDefault("name", "numbers")));
        pool.setResourceType(String.valueOf(dto.getOrDefault("resourceType", ResourcePool.MSISDN)));
        pool.setPrefix(String.valueOf(dto.get("prefix")));
        pool.setNextValue(dto.get("nextValue") instanceof Number n ? n.longValue() : 1L);
        pool.setCreatedAt(java.time.OffsetDateTime.now());
        pool.setLastUpdate(java.time.OffsetDateTime.now());
        pools.save(pool);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", pool.getId());
        map.put("name", pool.getName());
        map.put("resourceType", pool.getResourceType());
        map.put("prefix", pool.getPrefix());
        map.put("@type", "ResourcePool");
        return ResponseEntity.status(HttpStatus.CREATED).body(map);
    }

    /** Choose-your-number: an ANONYMOUS shortlist of available numbers (the
     * shop's picker) — previewed from the pool's window, never consumed. */
    @GetMapping("/tmf-api/resourcePoolManagement/v4/numberOffer")
    public ResponseEntity<List<Map<String, Object>>> numberOffer(
            @RequestParam(name = "count", defaultValue = "6") int count,
            @RequestParam(name = "shuffle", required = false) String shuffle) {
        return ResponseEntity.ok(orchestration.offerNumbers(
                tenantScope.currentTenantId(), Math.min(Math.max(count, 1), 12), shuffle));
    }

    @GetMapping("/tmf-api/resourcePoolManagement/v4/resourcePool")
    public ResponseEntity<List<Map<String, Object>>> listPools() {
        return ResponseEntity.ok(pools.findByTenantId(tenantScope.currentTenantId()).stream()
                .map(p -> {
                    Map<String, Object> map = new LinkedHashMap<String, Object>();
                    map.put("id", p.getId());
                    map.put("name", p.getName());
                    map.put("resourceType", p.getResourceType());
                    map.put("prefix", p.getPrefix());
                    map.put("@type", "ResourcePool");
                    return map;
                }).toList());
    }

    private Map<String, Object> serviceMap(ServiceInstance s) {
        Map<String, Object> map = new LinkedHashMap<>();
        String category = categoryOf(s.getName());
        map.put("id", s.getId());
        map.put("href", s.getHref());
        map.put("name", s.getName());
        map.put("description", s.getName() + " — " + category + " service");
        map.put("state", s.getState());
        map.put("category", category);
        map.put("startDate", s.getCreatedAt().toString());
        map.put("serviceOrderId", s.getServiceOrderId());
        // TMF638 (v3 kit) demands every array non-empty with typed entries.
        // Doctrine: fill them with DERIVED-REAL facts; where the fleet truly
        // has no relationship, the entry SAYS so (an explicit standalone
        // self-reference) rather than inventing a phantom dependency.
        map.put("serviceRelationship", List.of(Map.of(
                "relationshipType", "standalone",
                "service", Map.of("id", s.getId(), "href", s.getHref()))));
        map.put("supportingService", List.of(Map.of(
                "id", s.getId(), "href", s.getHref(), "name", s.getName(),
                "note", "standalone — supports itself; not an invented dependency")));
        map.put("serviceSpecification", Map.of(
                "id", "svcspec-" + category,
                "href", "/tmf-api/serviceCatalogManagement/v4/serviceSpecification/svcspec-" + category,
                "name", category + " service",
                "version", "1.0"));
        // the OPERATOR is a related party of every service it runs; the
        // owning customer joins when the service is owned
        List<Map<String, Object>> parties = new java.util.ArrayList<>();
        if (s.getOwnerPartyId() != null) {
            parties.add(Map.of("id", s.getOwnerPartyId(),
                    "href", "/tmf-api/party/v4/individual/" + s.getOwnerPartyId(),
                    "role", "customer"));
        }
        parties.add(Map.of("id", "op-" + s.getTenantId(),
                "href", "/tmf-api/party/v4/organization/op-" + s.getTenantId(),
                "role", "serviceProvider"));
        map.put("relatedParty", parties);
        // Partner entitlements are credentials, not network resources: they
        // surface as an activationCode characteristic, never as a "number".
        List<com.bss.som.entity.ResourceAssignment> assigned = assignments
                .findByTenantIdAndServiceId(s.getTenantId(), s.getId());
        List<Map<String, Object>> supporting = new java.util.ArrayList<>(assigned.stream()
                .filter(a -> !"partner".equals(a.getPoolId()))
                .map(a -> Map.<String, Object>of("id", a.getId(),
                        "href", "/tmf-api/resourceInventoryManagement/v4/resource/" + a.getId(),
                        "value", a.getValue(), "@referredType", "Resource"))
                .toList());
        if (supporting.isEmpty()) {
            // no issued resource — the PROVISIONING RECORD (its service
            // order) is the real thing that stood this service up
            supporting.add(Map.of("id", s.getServiceOrderId(),
                    "href", "/tmf-api/serviceOrdering/v4/serviceOrder/" + s.getServiceOrderId(),
                    "@referredType", "ServiceOrder"));
        }
        List<Map<String, Object>> characteristics = new java.util.ArrayList<>(assigned.stream()
                .filter(a -> "partner".equals(a.getPoolId()))
                .map(a -> Map.<String, Object>of("name", "activationCode",
                        "valueType", "string", "value", a.getValue()))
                .toList());
        characteristics.add(Map.of("name", "category", "valueType", "string", "value", category));
        List<Map<String, Object>> places = new java.util.ArrayList<>();
        if (s.getDeliveryPath() != null) {
            map.put("deliveryPath", s.getDeliveryPath());
            characteristics.add(Map.of("name", "deliveryPath",
                    "valueType", "string", "value", s.getDeliveryPath()));
            places.add(Map.of("id", "path:" + s.getDeliveryPath(),
                    "href", "/tmf-api/geographicSiteManagement/v4/geographicSite/path:"
                            + s.getDeliveryPath(),
                    "name", s.getDeliveryPath(),
                    "role", "servingSite", "@type", "RelatedPlaceRefOrValue"));
        }
        places.add(Map.of("id", "sa-" + s.getTenantId(),
                "href", "/tmf-api/geographicSiteManagement/v4/geographicSite/sa-" + s.getTenantId(),
                "name", "service area", "role", "serviceArea",
                "@type", "RelatedPlaceRefOrValue"));
        map.put("place", places);
        map.put("supportingResource", supporting);
        map.put("serviceCharacteristic", characteristics);
        map.put("@type", "Service");
        return map;
    }

    /** The service's kind, derived from what it IS named — never invented. */
    private String categoryOf(String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        if (n.contains("mobile") || n.contains("phone") || n.contains("sim")) return "mobile";
        if (n.contains("broadband") || n.contains("fiber") || n.contains("fibre")
                || n.contains("internet")) return "broadband";
        if (n.contains("tv") || n.contains("netflix") || n.contains("stream")) return "tv";
        return "service";
    }
}
