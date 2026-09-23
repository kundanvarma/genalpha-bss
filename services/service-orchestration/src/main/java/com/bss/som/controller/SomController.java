package com.bss.som.controller;

import com.bss.som.api.ApiConstants;
import com.bss.som.api.Projection;
import com.bss.som.dto.LineReceipts.CpeRestart;
import com.bss.som.dto.LineReceipts.CpeView;
import com.bss.som.dto.LineReceipts.ErrorView;
import com.bss.som.dto.LineReceipts.Finding;
import com.bss.som.dto.LineReceipts.NumberChange;
import com.bss.som.dto.LineReceipts.NumberOffer;
import com.bss.som.dto.LineReceipts.NumberOwner;
import com.bss.som.dto.LineReceipts.ServiceDiagnosis;
import com.bss.som.dto.LineReceipts.ServiceRestriction;
import com.bss.som.dto.LineReceipts.ServiceStateReceipt;
import com.bss.som.dto.LineReceipts.ServiceTransfer;
import com.bss.som.dto.LineReceipts.SimPinReset;
import com.bss.som.dto.LineReceipts.SimReplacement;
import com.bss.som.dto.LineReceipts.SimView;
import com.bss.som.dto.LineRequests.MigrateRequest;
import com.bss.som.dto.LineRequests.PoolRequest;
import com.bss.som.dto.LineRequests.RestrictRequest;
import com.bss.som.dto.LineRequests.SimPinRequest;
import com.bss.som.dto.LineRequests.SimReplaceRequest;
import com.bss.som.dto.LineRequests.SuspendRequest;
import com.bss.som.dto.LineRequests.TerminateRequest;
import com.bss.som.dto.LineRequests.TransferRequest;
import com.bss.som.dto.PartyRef;
import com.bss.som.dto.ServiceOrderView;
import com.bss.som.dto.ServiceView;
import com.bss.som.dto.StandardFaceViews.ResourcePoolView;
import com.bss.som.entity.ResourceAssignment;
import com.bss.som.entity.ResourcePool;
import com.bss.som.entity.ServiceInstance;
import com.bss.som.entity.ServiceOrder;
import com.bss.som.exception.BadRequestException;
import com.bss.som.exception.NotFoundException;
import com.bss.som.mapper.ServiceViews;
import com.bss.som.repository.ResourceAssignmentRepository;
import com.bss.som.repository.ResourcePoolRepository;
import com.bss.som.repository.ServiceInstanceRepository;
import com.bss.som.repository.ServiceOrderRepository;
import com.bss.som.security.PartyScope;
import com.bss.som.security.TenantScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
    /** the equipment seam — field-injected so the wide constructor stays as it is */
    @org.springframework.beans.factory.annotation.Autowired
    private com.bss.som.client.CpeClient cpe;
    private final com.bss.som.service.OrchestrationService orchestration;
    private final com.bss.som.repository.SimCardRepository sims;
    private final com.bss.som.client.SimPlatformClient simPlatform;
    private final com.bss.som.crypto.PukVault pukVault;
    private final com.bss.som.repository.NumberQuarantineRepository quarantine;
    private final com.bss.som.client.PartyOrgClient partyOrg;
    private final com.bss.som.client.OcsProvisioningClient ocs;
    private final com.bss.som.client.EntitlementClient entitlement;
    private final com.bss.som.client.DiagnosticsClients diagnostics;
    private final ServiceViews serviceViews;
    private final ObjectMapper objectMapper;

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
            com.bss.som.client.EntitlementClient entitlement,
            com.bss.som.client.DiagnosticsClients diagnostics,
            ServiceViews serviceViews, ObjectMapper objectMapper) {
        this.serviceOrders = serviceOrders;
        this.services = services;
        this.entitlement = entitlement;
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
        this.serviceViews = serviceViews;
        this.objectMapper = objectMapper;
    }

    private static String masked(String iccid) {
        return "•••• " + iccid.substring(iccid.length() - 5);
    }

    /**
     * The SIM behind a numbered service: masked ICCID by default; the PUK
     * only with ?reveal=true. Owner-checked — a customer token addresses only
     * their own service, and a foreign id is a 404, never a 403.
     */
    @GetMapping(ApiConstants.INVENTORY_BASE + "/service/{id}/sim")
    public ResponseEntity<SimView> sim(@PathVariable String id,
            @RequestParam(name = "reveal", defaultValue = "false") boolean reveal) {
        com.bss.som.entity.SimCard sim = requireOwnSim(id);
        String puk = null;
        if (reveal) {
            puk = pukVault.reveal(sim.getPuk(), sim.getIccid());
            // legacy plaintext row? upgrade it now that we've touched it
            if (!pukVault.isEncrypted(sim.getPuk())) {
                sim.setPuk(pukVault.encrypt(sim.getPuk(), sim.getIccid()));
                sim.setLastUpdate(OffsetDateTime.now());
                sims.save(sim);
            }
        }
        return ResponseEntity.ok(new SimView(id, masked(sim.getIccid()), puk, "SimCard"));
    }

    /**
     * OTA PIN change through the SIM-platform seam. The PIN goes to the card,
     * never stored or logged in the BSS.
     */
    @PostMapping(ApiConstants.INVENTORY_BASE + "/service/{id}/sim/resetPin")
    public ResponseEntity<SimPinReset> resetPin(@PathVariable String id, @RequestBody SimPinRequest body) {
        com.bss.som.entity.SimCard sim = requireOwnSim(id);
        String pin = body.newPin() == null ? "" : body.newPin();
        if (!pin.matches("\\d{4,8}")) {
            throw new BadRequestException("newPin must be 4-8 digits");
        }
        if (!simPlatform.resetPin(sim.getIccid(), pin)) {
            throw new BadRequestException("the SIM platform refused the PIN change");
        }
        // the OWNER rides the event so the customer is told their PIN
        // changed — a silent credential change is a gift to fraudsters
        String owner = services.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .map(ServiceInstance::getOwnerPartyId).orElse(null);
        Map<String, Object> pinEvent = new LinkedHashMap<>();
        pinEvent.put("serviceId", id);
        pinEvent.put("iccid", masked(sim.getIccid()));
        if (owner != null) {
            pinEvent.put("relatedParty", List.of(PartyRef.customer(owner)));
        }
        events.publish("SimPinResetEvent", "sim", pinEvent);
        return ResponseEntity.ok(SimPinReset.done());
    }

    /**
     * SIM replacement — the classic call. The NUMBER lives on the service;
     * the card is expendable: the old one is BLOCKED at the platform FIRST
     * (a lost card must die before anything else happens), a fresh card is
     * minted against the same service, and the owner is told on every
     * channel — a silent SIM swap is the textbook account-takeover.
     */
    @PostMapping(ApiConstants.INVENTORY_BASE + "/service/{id}/sim/replace")
    public ResponseEntity<SimReplacement> replaceSim(@PathVariable String id, @RequestBody SimReplaceRequest body) {
        String reason = body.reason() == null ? "lost" : body.reason();
        if (!Set.of("lost", "stolen", "damaged", "upgrade").contains(reason)) {
            throw new BadRequestException("reason must be lost, stolen, damaged or upgrade");
        }
        com.bss.som.entity.SimCard old = requireOwnSim(id);
        if (!simPlatform.block(old.getIccid())) {
            throw new BadRequestException("the SIM platform refused to block the old card — nothing was replaced");
        }
        old.setStatus(Set.of("lost", "stolen").contains(reason) ? "blocked" : "replaced");
        old.setReplacedReason(reason);
        old.setLastUpdate(OffsetDateTime.now());
        sims.save(old);
        String tenant = tenantScope.currentTenantId();
        com.bss.som.entity.SimCard fresh = orchestration.mintSim(tenant, id);
        // the phone's entitlements follow the new card
        entitlement.rebind(tenant, id, fresh.getIccid());
        String owner = services.findByIdAndTenantId(id, tenant)
                .map(ServiceInstance::getOwnerPartyId).orElse(null);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("serviceId", id);
        event.put("reason", reason);
        event.put("oldIccid", masked(old.getIccid()));
        event.put("iccid", masked(fresh.getIccid()));
        if (owner != null) {
            event.put("relatedParty", List.of(PartyRef.customer(owner)));
        }
        events.publish("SimReplacedEvent", "sim", event);
        return ResponseEntity.ok(new SimReplacement(id, reason,
                new SimReplacement.OldSim(masked(old.getIccid()), old.getStatus()), masked(fresh.getIccid()),
                "the new card is active; its PUK is revealable the usual way", "SimReplacement"));
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
    public ResponseEntity<NumberChange> changeNumber(@PathVariable String id) {
        String tenant = tenantScope.currentTenantId();
        ServiceInstance instance = requireOwnService(id);
        ResourceAssignment current = assignments.findByTenantIdAndServiceId(tenant, id).stream()
                .filter(a -> !"partner".equals(a.getPoolId()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("this service carries no number to change"));
        ResourcePool pool = pools.findFirstByTenantIdAndResourceType(tenant, ResourcePool.MSISDN)
                .orElseThrow(() -> new BadRequestException("no number pool configured for this tenant"));
        String oldNumber = current.getValue();
        // the old number goes on the shelf, with its story
        com.bss.som.entity.NumberQuarantine shelf = new com.bss.som.entity.NumberQuarantine();
        shelf.setId(UUID.randomUUID().toString());
        shelf.setTenantId(tenant);
        shelf.setNumber(oldNumber);
        shelf.setServiceId(id);
        shelf.setReason("numberChange");
        shelf.setReleasedAt(OffsetDateTime.now());
        quarantine.save(shelf);
        // the same assignment row carries the new draw — holder unchanged
        String fresh = pool.getPrefix() + String.format("%06d", pool.getNextValue());
        pool.setNextValue(pool.getNextValue() + 1);
        pool.setLastUpdate(OffsetDateTime.now());
        pools.save(pool);
        current.setPoolId(pool.getId());
        current.setValue(fresh);
        current.setAssignedAt(OffsetDateTime.now());
        assignments.save(current);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("serviceId", id);
        event.put("oldNumber", oldNumber);
        event.put("number", fresh);
        if (instance.getOwnerPartyId() != null) {
            event.put("relatedParty", List.of(PartyRef.customer(instance.getOwnerPartyId())));
        }
        events.publish("NumberChangedEvent", "service", event);
        return ResponseEntity.ok(new NumberChange(id, oldNumber, fresh,
                "the old number is quarantined; SIM, usage and billing are untouched", "NumberChange"));
    }

    /** Vacation hold: pause the line — number and SIM stay yours, charging
     * pauses, and the hold lifts itself at the agreed date (max 90 days). */
    @PostMapping(ApiConstants.INVENTORY_BASE + "/service/{id}/suspend")
    public ResponseEntity<ServiceStateReceipt> suspend(@PathVariable String id,
            @RequestBody(required = false) SuspendRequest body) {
        ServiceInstance instance = requireOwnService(id);
        SuspendRequest dto = body == null ? SuspendRequest.EMPTY : body;
        String reason = dto.reason() == null ? "vacation" : dto.reason();
        OffsetDateTime resumeAt = null;
        if (dto.until() != null) {
            try {
                resumeAt = OffsetDateTime.parse(dto.until());
            } catch (Exception e) {
                throw new BadRequestException("until must be an ISO date-time");
            }
        } else if (dto.days() != null) {
            resumeAt = OffsetDateTime.now().plusDays(dto.days());
        }
        if (resumeAt != null && (resumeAt.isBefore(OffsetDateTime.now())
                || resumeAt.isAfter(OffsetDateTime.now().plusDays(90)))) {
            throw new BadRequestException("the hold must end in the future and within 90 days");
        }
        return ResponseEntity.ok(orchestration.suspend(instance, reason, resumeAt));
    }

    /** Lift the hold early — or at all, when no end date was set. */
    @PostMapping(ApiConstants.INVENTORY_BASE + "/service/{id}/resume")
    public ResponseEntity<ServiceStateReceipt> resume(@PathVariable String id) {
        return ResponseEntity.ok(orchestration.resume(requireOwnService(id), "request"));
    }

    /**
     * RESTRICT — the enforcement primitive under a nonpayment case, lighter
     * than suspend: the line stays up but carries a barring profile (outgoing
     * barred / data throttled). Emergency numbers are ALWAYS whitelisted —
     * the profile cannot switch that off. Back-office/machine only
     * (service:write); customers never bar their own line this way.
     */
    @PostMapping(ApiConstants.INVENTORY_BASE + "/service/{id}/restrict")
    public ResponseEntity<ServiceRestriction> restrict(@PathVariable String id,
            @RequestBody(required = false) RestrictRequest body) {
        String tenant = tenantScope.currentTenantId();
        ServiceInstance instance = services.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> NotFoundException.forResource("Service", id));
        if (!ServiceInstance.ACTIVE.equals(instance.getState())) {
            throw new BadRequestException(
                    "only an active service can be restricted (state: " + instance.getState() + ")");
        }
        RestrictRequest dto = body == null ? RestrictRequest.EMPTY : body;
        String reason = dto.reason() == null ? "nonpayment" : dto.reason();
        Map<String, Object> profile = new LinkedHashMap<>();
        if (dto.profile() != null) {
            profile.putAll(dto.profile());
        }
        profile.putIfAbsent("outgoingBarred", true);
        profile.putIfAbsent("dataThrottled", true);
        profile.put("emergencyWhitelist", true); // statutory — not a knob
        instance.setRestrictedAt(OffsetDateTime.now());
        instance.setRestrictionReason(reason);
        try {
            instance.setRestrictionProfileJson(objectMapper.writeValueAsString(profile));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BadRequestException("unserializable restriction profile");
        }
        instance.setLastUpdate(OffsetDateTime.now());
        services.save(instance);
        ServiceRestriction event = new ServiceRestriction(instance.getId(), instance.getName(), instance.getState(),
                reason, profile, PartyRef.customerListOrNull(instance.getOwnerPartyId()), null);
        events.publish("ServiceRestrictedEvent", "service", event);
        return ResponseEntity.ok(event.labelled());
    }

    /** Lift the barring profile (the cure path, or an operator's hand). */
    @PostMapping(ApiConstants.INVENTORY_BASE + "/service/{id}/unrestrict")
    public ResponseEntity<ServiceRestriction> unrestrict(@PathVariable String id) {
        String tenant = tenantScope.currentTenantId();
        ServiceInstance instance = services.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> NotFoundException.forResource("Service", id));
        if (instance.getRestrictedAt() == null) {
            throw new BadRequestException("this service is not restricted");
        }
        instance.setRestrictedAt(null);
        instance.setRestrictionReason(null);
        instance.setRestrictionProfileJson(null);
        instance.setLastUpdate(OffsetDateTime.now());
        services.save(instance);
        ServiceRestriction event = new ServiceRestriction(instance.getId(), instance.getName(), instance.getState(),
                null, null, PartyRef.customerListOrNull(instance.getOwnerPartyId()), null);
        events.publish("ServiceUnrestrictedEvent", "service", event);
        return ResponseEntity.ok(event.labelled());
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
    public ResponseEntity<ServiceTransfer> transfer(@PathVariable String id, @RequestBody TransferRequest body) {
        String to = body.toPartyId();
        if (to == null || to.isBlank()) {
            throw new BadRequestException("toPartyId is required — who gets the line?");
        }
        String tenant = tenantScope.currentTenantId();
        ServiceInstance instance = services.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> NotFoundException.forResource("Service", id));
        if (!ServiceInstance.ACTIVE.equals(instance.getState())) {
            throw new BadRequestException(
                    "only an active line can be transferred (state: " + instance.getState() + ")");
        }
        String from = instance.getOwnerPartyId();
        if (to.equals(from)) {
            throw new BadRequestException("the line already belongs to them");
        }
        // the target must be REAL — a typo must not orphan a line
        if (partyOrg.individualOf(to).isEmpty()) {
            throw new BadRequestException("the receiving person is not on record — check the id");
        }
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        boolean isBusinessAdmin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "business:admin".equals(a.getAuthority()));
        Optional<String> scoped = partyScope.scopedPartyId();
        if (isBusinessAdmin) {
            // inside the SAME company only — all three org facts from party data
            String adminOrg = partyOrg.orgOf(auth.getName()).orElse(null);
            if (adminOrg == null
                    || !adminOrg.equals(partyOrg.orgOf(from).orElse(null))
                    || !adminOrg.equals(partyOrg.orgOf(to).orElse(null))) {
                throw NotFoundException.forResource("Service", id);
            }
        } else if (scoped.isPresent() && !scoped.get().equals(from)) {
            throw NotFoundException.forResource("Service", id);
        }
        instance.setOwnerPartyId(to);
        instance.setLastUpdate(OffsetDateTime.now());
        services.save(instance);
        String number = null;
        for (ResourceAssignment a : assignments.findByTenantIdAndServiceId(tenant, id)) {
            number = a.getValue();
            a.setOwnerPartyId(to);
            assignments.save(a);
        }
        ocs.transfer(tenant, id, to);
        ServiceTransfer event = new ServiceTransfer(id, instance.getName(), number,
                List.of(PartyRef.of(from, "giver"), PartyRef.of(to, "receiver")), null);
        events.publish("ServiceTransferredEvent", "serviceTransfer", event);
        return ResponseEntity.ok(event.labelled());
    }

    /**
     * "MY INTERNET IS SLOW": triage before ticket, in the order a good
     * tech-support agent thinks — is the line even on? is there a KNOWN
     * outage on its path? are they simply OUT OF DATA (the classic)? Only
     * an all-clear earns "raise a ticket and we will dig". Every check
     * that cannot run says so; a diagnosis never invents an all-clear.
     */
    @PostMapping(ApiConstants.INVENTORY_BASE + "/service/{id}/diagnose")
    public ResponseEntity<ServiceDiagnosis> diagnose(@PathVariable String id) {
        ServiceInstance instance = requireOwnService(id);
        String tenant = tenantScope.currentTenantId();
        List<Finding> findings = new ArrayList<>();
        String verdict = "allClear";

        if (ServiceInstance.SUSPENDED.equals(instance.getState())) {
            findings.add(Finding.cause("paused", "This line is PAUSED" + (instance.getResumeAt() != null
                    ? " until " + instance.getResumeAt().toLocalDate() : "")
                    + " — nothing flows while it sleeps. Resume it to get moving."));
            verdict = "paused";
        } else if (!ServiceInstance.ACTIVE.equals(instance.getState())) {
            findings.add(Finding.cause("notActive", "This service is " + instance.getState() + "."));
            verdict = "notActive";
        }

        var problems = diagnostics.openProblems();
        if (problems.isEmpty()) {
            findings.add(Finding.caution("assuranceUnreachable", "Could not check for network outages right now."));
        } else {
            Map<String, Object> onPath = instance.getDeliveryPath() == null ? null
                    : problems.get().stream()
                            .filter(p -> instance.getDeliveryPath().equals(String.valueOf(p.get("affectedObject"))))
                            .findFirst().orElse(null);
            if (onPath != null) {
                findings.add(Finding.cause("outage", "KNOWN OUTAGE on your line's path ("
                        + onPath.get("affectedObject") + "): "
                        + onPath.getOrDefault("description", "crews are on it")
                        + ". No ticket needed — it is already being worked."));
                if ("allClear".equals(verdict)) {
                    verdict = "outage";
                }
            } else if (!problems.get().isEmpty()) {
                findings.add(Finding.caution("areaIncidents", problems.get().size()
                        + " network incident(s) are open in the"
                        + " area — your line is not directly on an affected path, but"
                        + " conditions may be degraded."));
            }
        }

        // the box at the customer's end: a broadband line whose router is offline is not a network fault
        if ("broadband".equals(ServiceViews.categoryOf(instance.getName())) && cpe != null && cpe.enabled()) {
            var box = cpe.state(tenant, id);
            if (box.isEmpty()) {
                findings.add(Finding.caution("routerUnreachable",
                        "Could not reach the equipment system to check your router right now."));
            } else if ("offline".equals(box.get().state())) {
                findings.add(Finding.cause("routerOffline", "Your router (" + box.get().model()
                        + ") is OFFLINE — the network side is fine."
                        + " Check its power and cable, or restart it from here; last seen " + box.get().lastSeen() + "."));
                if ("allClear".equals(verdict)) {
                    verdict = "routerOffline";
                }
            } else if ("rebooting".equals(box.get().state())) {
                findings.add(Finding.caution("routerRebooting", "Your router is restarting — give it a minute."));
            } else {
                findings.add(Finding.info("routerOnline", "Router " + box.get().model() + " online, up "
                        + (box.get().uptimeSeconds() / 86400) + " day(s), "
                        + box.get().wifiClients() + " device(s) on Wi-Fi"
                        + (box.get().firmwareOutdated() ? " — a firmware update is pending" : "") + "."));
            }
        }

        diagnostics.bucketOf(tenant, id).ifPresent(bucket -> {
            double used = asDouble(bucket.get("usedGB"));
            double total = asDouble(bucket.get("totalGB")) + asDouble(bucket.get("rolloverGB"));
            if (total > 0 && used >= total) {
                findings.add(Finding.cause("outOfData", "You are OUT OF INCLUDED DATA (" + used + " of " + total
                        + " GB used) — speed is reduced until the next cycle."
                        + " A top-up restores full speed immediately."));
            } else if (total > 0 && used / total >= 0.9) {
                findings.add(Finding.caution("nearDataCap", String.format("%.0f%% of your data is used (%.1f of %.1f GB)"
                        + " — speed drops when it runs out.", used / total * 100, used, total)));
            }
        });
        if ("allClear".equals(verdict) && findings.stream().anyMatch(f -> "outOfData".equals(f.code()))) {
            verdict = "throttled";
        }

        if (findings.isEmpty()) {
            findings.add(Finding.info("allClear", "No known fault from here: line active, no outage on your path,"
                    + " data remaining. If it still feels slow, raise a ticket and we"
                    + " will dig deeper."));
        }
        return ResponseEntity.ok(new ServiceDiagnosis(id, instance.getName(), verdict, findings, "ServiceDiagnosis"));
    }

    private static double asDouble(Object v) {
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }

    /** The router or ONT on this line, as the ACS sees it — the customer's own, or any line for staff. */
    @GetMapping(ApiConstants.INVENTORY_BASE + "/service/{id}/cpe")
    public ResponseEntity<Object> cpeState(@PathVariable String id) {
        ServiceInstance instance = requireOwnService(id);
        if (cpe == null || !cpe.enabled()) {
            return ResponseEntity.notFound().build();
        }
        var box = cpe.state(tenantScope.currentTenantId(), instance.getId());
        if (box.isEmpty()) {
            return ResponseEntity.status(503).body(ErrorView.of(503, "equipment system unreachable"));
        }
        var b = box.get();
        return ResponseEntity.ok(new CpeView(instance.getId(), b.state(), b.uptimeSeconds(), b.firmware(),
                b.firmwareOutdated(), b.wifiClients(), b.model(), b.serial(), b.lastSeen(),
                "CustomerPremisesEquipment"));
    }

    /** Restart the router on this line — the customer on their own line, or care; logged as an event. */
    @PostMapping(ApiConstants.INVENTORY_BASE + "/service/{id}/cpe/restart")
    public ResponseEntity<Object> cpeRestart(@PathVariable String id) {
        ServiceInstance instance = requireOwnService(id);
        if (cpe == null || !cpe.enabled()) {
            return ResponseEntity.notFound().build();
        }
        if (!"active".equalsIgnoreCase(instance.getState())) {
            return ResponseEntity.status(409).body(ErrorView.of(409, "the line is not active"));
        }
        boolean accepted = cpe.reboot(tenantScope.currentTenantId(), instance.getId());
        if (!accepted) {
            return ResponseEntity.status(503).body(ErrorView.of(503, "the equipment system did not accept the restart"));
        }
        events.publish("CpeRestartedEvent", "service", Map.of("id", instance.getId(), "name", instance.getName(),
                "relatedParty", List.of(Map.of("id", String.valueOf(instance.getOwnerPartyId()), "role", "customer"))));
        return ResponseEntity.accepted().body(CpeRestart.sent(instance.getId()));
    }

    private ServiceInstance requireOwnService(String serviceId) {
        ServiceInstance instance = services.findByIdAndTenantId(serviceId, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Service", serviceId));
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(instance.getOwnerPartyId())) {
                throw NotFoundException.forResource("Service", serviceId);
            }
        });
        return instance;
    }

    private com.bss.som.entity.SimCard requireOwnSim(String serviceId) {
        String tenant = tenantScope.currentTenantId();
        requireOwnService(serviceId);
        // one ACTIVE card per service; blocked/replaced rows keep the history
        return sims.findFirstByTenantIdAndServiceIdAndStatus(tenant, serviceId, "active")
                .orElseThrow(() -> NotFoundException.forResource("SIM for service", serviceId));
    }

    @GetMapping(ApiConstants.ORDER_BASE + "/serviceOrder")
    public ResponseEntity<List<JsonNode>> serviceOrders(
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
                        .sorted(Comparator.comparing(ServiceOrder::getCreatedAt).reversed())
                        .toList();
        List<JsonNode> out = rows.stream()
                .filter(o -> externalId == null || unquote(externalId).equals(o.getExternalId()))
                .filter(o -> priority == null || unquote(priority).equals(o.getPriority()))
                .map(this::orderView)
                .filter(v -> category == null || unquote(category).equals(String.valueOf(v.category())))
                .skip(offset).limit(limit)
                .map(v -> Projection.select(objectMapper, v, fields, "id"))
                .toList();
        return ResponseEntity.ok(out);
    }

    /**
     * TMF641 northbound: an external system files a service order directly
     * (no product order behind it). The order is RECORDED and acknowledged —
     * fulfilment of external orders is the caller's workflow, honestly
     * reflected in a state that never claims progress that didn't happen.
     * The /v3 alias serves the R18-era clients, same validation. The
     * caller's document is kept verbatim beside the row.
     */
    @PostMapping({ApiConstants.ORDER_BASE + "/serviceOrder", "/tmf-api/serviceOrdering/v3/serviceOrder"})
    public ResponseEntity<ServiceOrderView> createServiceOrder(@RequestBody ObjectNode dto) {
        JsonNode items = dto.get("orderItem");
        if (items == null || !items.isArray() || items.isEmpty()) {
            throw new BadRequestException("orderItem is required — an order orders SOMETHING");
        }
        for (JsonNode item : items) {
            JsonNode spec = item.path("service").path("serviceSpecification");
            if (spec.isObject() && spec.path("id").asText("").isBlank()) {
                throw new BadRequestException("serviceSpecification needs an id — a nameless spec specifies nothing");
            }
        }
        String tenant = tenantScope.currentTenantId();
        ServiceOrder order = new ServiceOrder();
        String id = UUID.randomUUID().toString();
        order.setId(id);
        order.setTenantId(tenant);
        order.setHref(ApiConstants.ORDER_BASE + "/serviceOrder/" + id);
        order.setState("acknowledged");
        order.setProductOrderId("external");
        order.setItemName(text(dto, "category", "external"));
        order.setExternalId(text(dto, "externalId", null));
        order.setPriority(text(dto, "priority", null));
        order.setDescription(text(dto, "description", null));
        try {
            order.setDocumentJson(objectMapper.writeValueAsString(dto));
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new BadRequestException("unserializable order document");
        }
        order.setCreatedAt(OffsetDateTime.now());
        order.setLastUpdate(OffsetDateTime.now());
        serviceOrders.save(order);
        return ResponseEntity.status(HttpStatus.CREATED).body(orderView(order));
    }

    /** A posted scalar as text (its JSON rendering for a non-text node), or the fallback when absent/null. */
    private static String text(JsonNode node, String key, String fallback) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) {
            return fallback;
        }
        return v.isValueNode() ? v.asText() : v.toString();
    }

    @GetMapping({ApiConstants.ORDER_BASE + "/serviceOrder/{id}", "/tmf-api/serviceOrdering/v3/serviceOrder/{id}"})
    public ResponseEntity<JsonNode> serviceOrderById(@PathVariable String id,
            @RequestParam(required = false) String fields) {
        ServiceOrder order = serviceOrders.findById(id)
                .filter(o -> tenantScope.currentTenantId().equals(o.getTenantId()))
                .orElseThrow(() -> NotFoundException.forResource("ServiceOrder", id));
        return ResponseEntity.ok(Projection.select(objectMapper, orderView(order), fields, "id"));
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
    public ResponseEntity<NumberOwner> numberOwner(@RequestParam String number) {
        if (partyScope.scopedPartyId().isPresent()) {
            throw NotFoundException.forResource("Number", number);
        }
        // Tolerant matching: people type numbers without '+', and a '+' in a
        // query string arrives as a space — try the bare digits with a '+' too.
        String normalized = number.replaceAll("[\\s-]", "");
        String tenant = tenantScope.currentTenantId();
        return assignments.findFirstByTenantIdAndValue(tenant, normalized)
                .or(() -> normalized.startsWith("+") ? Optional.empty()
                        : assignments.findFirstByTenantIdAndValue(tenant, "+" + normalized))
                .filter(a -> a.getOwnerPartyId() != null)
                .map(a -> ResponseEntity.ok(new NumberOwner(a.getValue(), a.getOwnerPartyId())))
                .orElseThrow(() -> NotFoundException.forResource("Number", number));
    }

    @GetMapping({ApiConstants.INVENTORY_BASE + "/service", ApiConstants.INVENTORY_BASE + "/service/"})
    public ResponseEntity<List<JsonNode>> services(
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
                                .sorted(Comparator.comparing(ServiceInstance::getCreatedAt).reversed())
                                .toList()
                        : services.findAll().stream()
                                .filter(s -> tenant.equals(s.getTenantId()))
                                .sorted(Comparator.comparing(ServiceInstance::getCreatedAt).reversed())
                                .toList();
        List<JsonNode> out = rows.stream()
                .filter(s -> name == null || name.equals(s.getName()))
                .filter(s -> state == null || state.equals(s.getState()))
                .map(serviceViews::view)
                .filter(v -> category == null || category.equals(v.category()))
                .skip(offset).limit(limit)
                // TMF630 attribute selection: id and href always ride along
                .map(v -> Projection.selectExact(objectMapper, v, fields, "id", "href"))
                .toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping(ApiConstants.INVENTORY_BASE + "/service/{id}")
    public ResponseEntity<ServiceView> serviceById(@PathVariable String id) {
        return ResponseEntity.ok(serviceViews.view(requireOwnService(id)));
    }

    /**
     * The self-healing hook: re-home a service to a new delivery point.
     * Assurance calls this when the current path fails — fibre cut, edge
     * takes over, SLA restored. Machine or staff only (service:write).
     */
    @PostMapping(ApiConstants.INVENTORY_BASE + "/service/{id}/migrate")
    public ResponseEntity<ServiceView> migrate(@PathVariable String id, @RequestBody MigrateRequest body) {
        if (body.deliveryPoint() == null || body.deliveryPoint().isBlank()) {
            throw new BadRequestException("deliveryPoint is required — where does the service move to?");
        }
        String tenant = tenantScope.currentTenantId();
        ServiceInstance instance = services.findById(id)
                .filter(s -> tenant.equals(s.getTenantId()))
                .orElseThrow(() -> NotFoundException.forResource("Service", id));
        String from = instance.getDeliveryPath();
        instance.setDeliveryPath(body.deliveryPoint());
        instance.setLastUpdate(OffsetDateTime.now());
        services.save(instance);
        events.publish("ServiceAttributeValueChangeEvent", "service", Map.of(
                "id", instance.getId(), "name", instance.getName(),
                "deliveryPath", instance.getDeliveryPath(),
                "previousDeliveryPath", from == null ? "" : from,
                "relatedParty", instance.getOwnerPartyId() == null ? List.of()
                        : List.of(Map.of("id", instance.getOwnerPartyId(), "role", "customer"))));
        return ResponseEntity.ok(serviceViews.view(instance));
    }

    /** Cease a service (disconnect) — staff, machine, or the OWNER
     * cancelling their own subscription; releases the number. */
    @PostMapping(ApiConstants.INVENTORY_BASE + "/service/{id}/terminate")
    public ResponseEntity<ServiceStateReceipt> terminate(@PathVariable String id,
            @RequestBody(required = false) TerminateRequest body) {
        requireOwnService(id); // scoped tokens cancel only their own line
        String reason = body == null || body.reason() == null ? "cease" : body.reason();
        return ResponseEntity.ok(orchestration.terminateService(id, reason));
    }

    /**
     * The TMF641 view of an order. orderItem rides every row: the posted
     * items for external orders (the caller's document, id/state/action
     * defaulted where absent), the single add-item an internal order
     * factually IS otherwise.
     */
    private ServiceOrderView orderView(ServiceOrder o) {
        List<JsonNode> items = null;
        if (o.getDocumentJson() != null) {
            try {
                JsonNode doc = objectMapper.readTree(o.getDocumentJson());
                JsonNode raw = doc.get("orderItem");
                if (raw != null && raw.isArray()) {
                    items = new ArrayList<>();
                    int n = 0;
                    for (JsonNode it : raw) {
                        n++;
                        if (!it.isObject()) {
                            continue;
                        }
                        ObjectNode item = it.deepCopy();
                        if (!item.has("id")) item.put("id", String.valueOf(n));
                        if (!item.has("state")) item.put("state", o.getState());
                        if (!item.has("action")) item.put("action", "add");
                        items.add(item);
                    }
                }
            } catch (com.fasterxml.jackson.core.JacksonException ignored) {
                // fall through to the derived item
            }
        }
        if (items == null || items.isEmpty()) {
            items = List.of(objectMapper.valueToTree(ServiceOrderView.DerivedItem.add(o.getState(), o.getItemName())));
        }
        return new ServiceOrderView(o.getId(), o.getHref(), o.getState(), o.getItemName(),
                o.getCreatedAt().toString(), o.getProductOrderId(), o.getExternalId(), o.getPriority(),
                o.getDescription(), o.getCompletedAt() == null ? null : o.getCompletedAt().toString(),
                items, "ServiceOrder");
    }

    @PostMapping("/tmf-api/resourcePoolManagement/v4/resourcePool")
    public ResponseEntity<ResourcePoolView> createPool(@RequestBody PoolRequest dto) {
        if (dto.prefix() == null) {
            throw new BadRequestException("prefix is required — a pool mints values from it");
        }
        ResourcePool pool = new ResourcePool();
        pool.setId(UUID.randomUUID().toString());
        pool.setTenantId(tenantScope.currentTenantId());
        pool.setHref("/tmf-api/resourcePoolManagement/v4/resourcePool/" + pool.getId());
        pool.setName(dto.name() == null ? "numbers" : dto.name());
        pool.setResourceType(dto.resourceType() == null ? ResourcePool.MSISDN : dto.resourceType());
        pool.setPrefix(dto.prefix());
        pool.setNextValue(dto.nextValue() == null ? 1L : dto.nextValue());
        pool.setCreatedAt(OffsetDateTime.now());
        pool.setLastUpdate(OffsetDateTime.now());
        pools.save(pool);
        return ResponseEntity.status(HttpStatus.CREATED).body(poolView(pool));
    }

    /** Choose-your-number: an ANONYMOUS shortlist of available numbers (the
     * shop's picker) — previewed from the pool's window, never consumed. */
    @GetMapping("/tmf-api/resourcePoolManagement/v4/numberOffer")
    public ResponseEntity<List<NumberOffer>> numberOffer(
            @RequestParam(name = "count", defaultValue = "6") int count,
            @RequestParam(name = "shuffle", required = false) String shuffle) {
        return ResponseEntity.ok(orchestration.offerNumbers(
                tenantScope.currentTenantId(), Math.min(Math.max(count, 1), 12), shuffle));
    }

    @GetMapping("/tmf-api/resourcePoolManagement/v4/resourcePool")
    public ResponseEntity<List<ResourcePoolView>> listPools() {
        return ResponseEntity.ok(pools.findByTenantId(tenantScope.currentTenantId()).stream()
                .map(this::poolView).toList());
    }

    private ResourcePoolView poolView(ResourcePool p) {
        return ResourcePoolView.of(p.getId(), p.getName(), p.getResourceType(), p.getPrefix());
    }
}
