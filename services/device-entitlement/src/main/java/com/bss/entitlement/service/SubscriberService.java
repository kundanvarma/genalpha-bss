package com.bss.entitlement.service;

import com.bss.entitlement.client.AucClient;
import com.bss.entitlement.entity.CompanionDevice;
import com.bss.entitlement.entity.EcsRequest;
import com.bss.entitlement.entity.EntitlementDevice;
import com.bss.entitlement.entity.EntitlementSubscriber;
import com.bss.entitlement.events.DomainEventPublisher;
import com.bss.entitlement.exception.BadRequestException;
import com.bss.entitlement.exception.NotFoundException;
import com.bss.entitlement.repository.CompanionDeviceRepository;
import com.bss.entitlement.repository.EcsRequestRepository;
import com.bss.entitlement.repository.EntitlementDeviceRepository;
import com.bss.entitlement.repository.EntitlementSubscriberRepository;
import com.bss.entitlement.security.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The BSS face of the entitlement server: bind an IMSI to a line, read what a
 * line is entitled to, see which devices checked in, ask a device to
 * re-configure itself. Tenant-scoped like everything else.
 */
@Service
public class SubscriberService {

    private final EntitlementSubscriberRepository subscribers;
    private final EntitlementDeviceRepository devices;
    private final CompanionDeviceRepository companions;
    private final EcsRequestRepository requests;
    private final EntitlementDecisionService decisions;
    private final TokenService tokens;
    private final AucClient auc;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final ObjectMapper mapper = new ObjectMapper();

    public SubscriberService(EntitlementSubscriberRepository subscribers, EntitlementDeviceRepository devices,
            CompanionDeviceRepository companions, EcsRequestRepository requests,
            EntitlementDecisionService decisions, TokenService tokens, AucClient auc,
            DomainEventPublisher events, TenantScope tenantScope) {
        this.subscribers = subscribers;
        this.devices = devices;
        this.companions = companions;
        this.requests = requests;
        this.decisions = decisions;
        this.tokens = tokens;
        this.auc = auc;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    /** Create or update the binding for an IMSI (the BSS's activation / SIM-swap / plan-change hook). */
    @Transactional
    public Map<String, Object> upsert(Map<String, Object> dto) {
        String tenant = tenantScope.currentTenantId();
        String imsi = str(dto.get("imsi"));
        if (imsi == null || !imsi.matches("\\d{6,15}")) {
            throw new BadRequestException("imsi (6-15 digits) is required");
        }
        EntitlementSubscriber s = subscribers.findByTenantIdAndImsi(tenant, imsi).orElseGet(() -> {
            EntitlementSubscriber fresh = new EntitlementSubscriber();
            fresh.setId(UUID.randomUUID().toString());
            fresh.setTenantId(tenant);
            fresh.setImsi(imsi);
            fresh.setCreatedAt(OffsetDateTime.now());
            return fresh;
        });
        boolean planChanged = dto.get("offeringId") != null && !String.valueOf(dto.get("offeringId")).equals(s.getOfferingId());
        if (dto.containsKey("msisdn")) s.setMsisdn(str(dto.get("msisdn")));
        if (dto.containsKey("iccid")) s.setIccid(str(dto.get("iccid")));
        if (dto.containsKey("partyId")) s.setPartyId(str(dto.get("partyId")));
        if (dto.containsKey("serviceId")) s.setServiceId(str(dto.get("serviceId")));
        if (dto.containsKey("offeringId")) s.setOfferingId(str(dto.get("offeringId")));
        if (dto.get("status") != null) s.setStatus(requireStatus(dto.get("status")));
        if (dto.get("imsProvisioned") != null) s.setImsProvisioned(bool(dto.get("imsProvisioned")));
        if (dto.get("emergencyAddressConfirmed") != null) s.setEmergencyAddressConfirmed(bool(dto.get("emergencyAddressConfirmed")));
        if (dto.get("termsAccepted") != null) s.setTermsAccepted(bool(dto.get("termsAccepted")));
        if (dto.containsKey("featureOverrides")) s.setFeatureOverrides(json(dto.get("featureOverrides")));
        // the AUC may know the identity better than the caller
        if (s.getIccid() == null || s.getMsisdn() == null) {
            auc.identity(imsi).ifPresent(id -> {
                if (s.getIccid() == null) s.setIccid(id.iccid());
                if (s.getMsisdn() == null) s.setMsisdn(id.msisdn());
            });
        }
        s.setLastUpdate(OffsetDateTime.now());
        subscribers.save(s);
        if (planChanged) {
            // a plan change is a new entitlement set: tell the devices (TS.43 §2.6)
            events.publish("EntitlementChangedEvent", "entitlementSubscriber", toMap(s, true));
        }
        return toMap(s, true);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String imsi, String partyId, String serviceId) {
        String tenant = tenantScope.currentTenantId();
        List<EntitlementSubscriber> rows;
        if (imsi != null) {
            rows = subscribers.findByTenantIdAndImsi(tenant, imsi).map(List::of).orElse(List.of());
        } else if (partyId != null) {
            rows = subscribers.findByTenantIdAndPartyId(tenant, partyId);
        } else if (serviceId != null) {
            rows = subscribers.findByTenantIdAndServiceId(tenant, serviceId);
        } else {
            rows = subscribers.findByTenantIdOrderByLastUpdateDesc(tenant);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (EntitlementSubscriber s : rows) {
            out.add(toMap(s, false));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public EntitlementSubscriber get(String imsi) {
        return subscribers.findByTenantIdAndImsi(tenantScope.currentTenantId(), imsi)
                .orElseThrow(() -> NotFoundException.forResource("EntitlementSubscriber", imsi));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> entitlements(String imsi) {
        EntitlementSubscriber s = get(imsi);
        Map<String, Object> out = new LinkedHashMap<>(toMap(s, false));
        out.put("entitlements", decisions.explain(s));
        Map<String, Object> ts43 = new LinkedHashMap<>();
        for (String app : List.of("ap2003", "ap2004", "ap2005", "ap2010")) {
            ts43.put(app, decisions.decide(s, app, null));
        }
        out.put("ts43", ts43);
        List<Map<String, Object>> companionList = new ArrayList<>();
        for (CompanionDevice c : companions.findByTenantIdAndImsi(s.getTenantId(), s.getImsi())) {
            companionList.add(OdsaService.companionMap(c, s));
        }
        out.put("companions", companionList);
        List<Map<String, Object>> deviceList = new ArrayList<>();
        for (EntitlementDevice d : devices.findByTenantIdAndImsi(s.getTenantId(), s.getImsi())) {
            deviceList.add(deviceMap(d));
        }
        out.put("devices", deviceList);
        return out;
    }

    /** Server-initiated re-configuration (TS.43 §2.6): the ECS asks the
     * subscriber's devices to fetch their entitlements again — a push token
     * when the device registered one, else the operator's SMS path; here the
     * intent is recorded and announced, the transport is the notification seam. */
    @Transactional
    public Map<String, Object> reconfigure(String imsi, List<String> apps) {
        EntitlementSubscriber s = get(imsi);
        List<Map<String, Object>> targets = new ArrayList<>();
        for (EntitlementDevice d : devices.findByTenantIdAndImsi(s.getTenantId(), s.getImsi())) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("terminalId", d.getTerminalId());
            t.put("channel", d.getNotifToken() != null && d.getNotifAction() != null && d.getNotifAction() > 0 ? "push" : "sms");
            targets.add(t);
            log(s.getTenantId(), d.getTerminalId(), s.getImsi(), String.join(",", apps), "Reconfigure", "notified",
                    "server-initiated entitlement refresh over " + t.get("channel"));
        }
        Map<String, Object> resource = new LinkedHashMap<>(toMap(s, false));
        resource.put("apps", apps);
        resource.put("targets", targets);
        events.publish("EntitlementReconfigureRequestedEvent", "entitlementSubscriber", resource);
        return resource;
    }

    /** Unbind an IMSI (a SIM retired for good): its tokens die with it. */
    @Transactional
    public void delete(String imsi) {
        EntitlementSubscriber s = get(imsi);
        tokens.revokeAll(s.getTenantId(), s.getImsi());
        subscribers.delete(s);
        events.publish("EntitlementChangedEvent", "entitlementSubscriber", Map.of("imsi", imsi, "status", "unbound"));
    }

    /** Revoke every device token of a line (a SIM swap, a termination): the next check-in re-runs EAP-AKA. */
    @Transactional
    public Map<String, Object> revokeTokens(String imsi) {
        EntitlementSubscriber s = get(imsi);
        int n = tokens.revokeAll(s.getTenantId(), s.getImsi());
        return Map.of("imsi", imsi, "revoked", n);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> devices() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EntitlementDevice d : devices.findTop200ByTenantIdOrderByLastSeenAtDesc(tenantScope.currentTenantId())) {
            out.add(deviceMap(d));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> companions() {
        List<Map<String, Object>> out = new ArrayList<>();
        String tenant = tenantScope.currentTenantId();
        for (CompanionDevice c : companions.findTop200ByTenantIdOrderByLastUpdateDesc(tenant)) {
            EntitlementSubscriber s = subscribers.findByTenantIdAndImsi(tenant, c.getImsi()).orElse(null);
            Map<String, Object> m = OdsaService.companionMap(c, s == null ? placeholder(c.getImsi()) : s);
            out.add(m);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> requests(String imsi) {
        String tenant = tenantScope.currentTenantId();
        List<EcsRequest> rows = imsi == null ? requests.findTop200ByTenantIdOrderByCreatedAtDesc(tenant)
                : requests.findTop50ByTenantIdAndImsiOrderByCreatedAtDesc(tenant, imsi);
        List<Map<String, Object>> out = new ArrayList<>();
        for (EcsRequest r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("terminalId", r.getTerminalId());
            m.put("imsi", r.getImsi());
            m.put("app", r.getApp());
            m.put("operation", r.getOperation());
            m.put("outcome", r.getOutcome());
            m.put("detail", r.getDetail());
            m.put("createdAt", r.getCreatedAt().toString());
            out.add(m);
        }
        return out;
    }

    /** Called from the event listener: a line's state changed in the orchestrator. */
    @Transactional
    public void lineState(String tenantId, String serviceId, String status) {
        for (EntitlementSubscriber s : subscribers.findByTenantIdAndServiceId(tenantId, serviceId)) {
            s.setStatus(status);
            s.setLastUpdate(OffsetDateTime.now());
            subscribers.save(s);
            if (EntitlementSubscriber.TERMINATED.equals(status)) {
                tokens.revokeAll(tenantId, s.getImsi());
            }
            events.publish("EntitlementChangedEvent", "entitlementSubscriber", toMap(s, true), tenantId);
        }
    }

    public void log(String tenantId, String terminalId, String imsi, String app, String operation, String outcome, String detail) {
        EcsRequest r = new EcsRequest();
        r.setId(UUID.randomUUID().toString());
        r.setTenantId(tenantId);
        r.setTerminalId(terminalId);
        r.setImsi(imsi);
        r.setApp(app == null ? null : app.length() > 64 ? app.substring(0, 64) : app);
        r.setOperation(operation);
        r.setOutcome(outcome);
        r.setDetail(detail == null ? null : detail.length() > 1000 ? detail.substring(0, 1000) : detail);
        r.setCreatedAt(OffsetDateTime.now());
        requests.save(r);
    }

    public Map<String, Object> toMap(EntitlementSubscriber s, boolean withExplanation) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("imsi", s.getImsi());
        m.put("msisdn", s.getMsisdn());
        m.put("iccid", s.getIccid());
        m.put("partyId", s.getPartyId());
        m.put("serviceId", s.getServiceId());
        m.put("offeringId", s.getOfferingId());
        m.put("status", s.getStatus());
        m.put("imsProvisioned", s.isImsProvisioned());
        m.put("emergencyAddressConfirmed", s.isEmergencyAddressConfirmed());
        m.put("termsAccepted", s.isTermsAccepted());
        if (s.getFeatureOverrides() != null) {
            try {
                m.put("featureOverrides", mapper.readValue(s.getFeatureOverrides(), Map.class));
            } catch (Exception ignore) { /* shown raw below */ }
        }
        m.put("createdAt", s.getCreatedAt() == null ? null : s.getCreatedAt().toString());
        m.put("lastUpdate", s.getLastUpdate() == null ? null : s.getLastUpdate().toString());
        if (withExplanation) {
            m.put("entitlements", decisions.explain(s));
        }
        return m;
    }

    static Map<String, Object> deviceMap(EntitlementDevice d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("terminalId", d.getTerminalId());
        m.put("imsi", d.getImsi());
        m.put("vendor", d.getVendor());
        m.put("model", d.getModel());
        m.put("swVersion", d.getSwVersion());
        m.put("pushRegistered", d.getNotifToken() != null && d.getNotifAction() != null && d.getNotifAction() > 0);
        m.put("lastApps", d.getLastApps());
        m.put("lastSeenAt", d.getLastSeenAt() == null ? null : d.getLastSeenAt().toString());
        return m;
    }

    private static EntitlementSubscriber placeholder(String imsi) {
        EntitlementSubscriber s = new EntitlementSubscriber();
        s.setImsi(imsi);
        return s;
    }

    private static String requireStatus(Object v) {
        String s = String.valueOf(v).trim().toLowerCase();
        if (!List.of(EntitlementSubscriber.ACTIVE, EntitlementSubscriber.SUSPENDED, EntitlementSubscriber.TERMINATED).contains(s)) {
            throw new BadRequestException("status must be active, suspended or terminated");
        }
        return s;
    }

    private String json(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return v instanceof String str ? str : mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new BadRequestException("featureOverrides must be a JSON object");
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static boolean bool(Object v) {
        return Boolean.parseBoolean(String.valueOf(v));
    }
}
