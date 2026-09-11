package com.bss.entitlement.service;

import com.bss.entitlement.client.AucClient;
import com.bss.entitlement.client.CommunicationClient;
import com.bss.entitlement.entity.CompanionDevice;
import com.bss.entitlement.entity.SubscriptionTransfer;
import com.bss.entitlement.repository.SubscriptionTransferRepository;
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
    private final SubscriptionTransferRepository transfers;
    private final CommunicationClient communication;
    private final ObjectMapper mapper = new ObjectMapper();

    public SubscriberService(EntitlementSubscriberRepository subscribers, EntitlementDeviceRepository devices,
            CompanionDeviceRepository companions, EcsRequestRepository requests,
            EntitlementDecisionService decisions, TokenService tokens, AucClient auc,
            DomainEventPublisher events, TenantScope tenantScope, SubscriptionTransferRepository transfers,
            CommunicationClient communication) {
        this.subscribers = subscribers;
        this.devices = devices;
        this.companions = companions;
        this.requests = requests;
        this.decisions = decisions;
        this.tokens = tokens;
        this.auc = auc;
        this.events = events;
        this.tenantScope = tenantScope;
        this.transfers = transfers;
        this.communication = communication;
    }

    /**
     * Create or update the binding — the BSS's activation / SIM-swap / plan-change
     * hook. Keyed by IMSI when the caller has one; otherwise by the LINE
     * ({@code serviceId}) with the SIM's ICCID, and the IMSI comes from the AUC
     * seam (a real HSS knows which IMSI a card carries; the dev AUC allocates
     * one). A new ICCID on a known line is a SIM swap: the binding moves to the
     * new IMSI and every device token of the old one dies.
     */
    @Transactional
    public Map<String, Object> upsert(Map<String, Object> dto) {
        String tenant = tenantScope.currentTenantId();
        String imsi = str(dto.get("imsi"));
        String serviceId = str(dto.get("serviceId"));
        String iccid = str(dto.get("iccid"));
        EntitlementSubscriber existing = null;
        if (imsi != null) {
            existing = subscribers.findByTenantIdAndImsi(tenant, imsi).orElse(null);
        }
        if (existing == null && serviceId != null) {
            existing = subscribers.findByTenantIdAndServiceId(tenant, serviceId).stream().findFirst().orElse(null);
        }
        if (imsi == null) {
            if (iccid != null && (existing == null || !iccid.equals(existing.getIccid()))) {
                // a (new) SIM on the line: ask the AUC which IMSI it carries
                imsi = auc.identityByIccid(tenant, iccid, str(dto.get("msisdn")) != null ? str(dto.get("msisdn")) : existing == null ? null : existing.getMsisdn())
                        .map(AucClient.Identity::imsi).orElse(null);
                if (imsi == null) {
                    throw new BadRequestException("the AUC does not know SIM " + iccid + " and no imsi was given");
                }
            } else if (existing != null) {
                imsi = existing.getImsi();
            }
        }
        if (imsi == null || !imsi.matches("\\d{6,15}")) {
            throw new BadRequestException("imsi (6-15 digits), or a serviceId with an iccid the AUC knows, is required");
        }
        if (existing != null && !imsi.equals(existing.getImsi())) {
            // SIM swap: the line keeps its binding, the identity changes, old devices must re-authenticate
            tokens.revokeAll(tenant, existing.getImsi());
            final String keepId = existing.getId();
            subscribers.findByTenantIdAndImsi(tenant, imsi).ifPresent(dup -> {
                if (!dup.getId().equals(keepId)) {
                    subscribers.delete(dup);
                }
            });
            existing.setImsi(imsi);
        }
        final String resolvedImsi = imsi;
        EntitlementSubscriber s = existing != null ? existing : subscribers.findByTenantIdAndImsi(tenant, imsi).orElseGet(() -> {
            EntitlementSubscriber fresh = new EntitlementSubscriber();
            fresh.setId(UUID.randomUUID().toString());
            fresh.setTenantId(tenant);
            fresh.setImsi(resolvedImsi);
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
            auc.identity(tenant, imsi).ifPresent(id -> {
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
        List<Map<String, Object>> transferList = new ArrayList<>();
        for (SubscriptionTransfer t : transfers.findByTenantIdAndImsiOrderByCreatedAtDesc(s.getTenantId(), s.getImsi())) {
            transferList.add(OdsaService.transferMap(t, s));
        }
        out.put("transfers", transferList);
        return out;
    }

    /** Server-initiated re-configuration (TS.43 §2.6): the ECS asks the
     * subscriber's devices to fetch their entitlements again — a push token
     * when the device registered one, else the operator's SMS path; here the
     * intent is recorded and announced, the transport is the notification seam. */
    @Transactional
    public Map<String, Object> reconfigure(String imsi, List<String> apps) {
        EntitlementSubscriber s = get(imsi);
        String payload;
        try {
            payload = mapper.writeValueAsString(Map.of("app", apps, "timestamp", OffsetDateTime.now().toString()));
        } catch (Exception e) {
            payload = "{\"app\":[],\"timestamp\":\"" + OffsetDateTime.now() + "\"}";
        }
        List<Map<String, Object>> targets = new ArrayList<>();
        List<EntitlementDevice> phones = devices.findByTenantIdAndImsi(s.getTenantId(), s.getImsi());
        if (phones.isEmpty() && s.getPartyId() != null) {
            // no phone has checked in yet: the line still gets the SMS
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("channel", "sms");
            t.put("messageId", communication.notifyRefresh(s.getPartyId(), "sms", payload, apps.stream().map(EcsService::appName).toList()));
            targets.add(t);
            log(s.getTenantId(), null, s.getImsi(), EcsService.appNames(apps), "Reconfigure",
                    t.get("messageId") == null ? "notify-failed" : "notified", "refresh notice by SMS to the line");
        }
        for (EntitlementDevice d : phones) {
            boolean push = d.getNotifToken() != null && d.getNotifAction() != null && d.getNotifAction() > 0;
            String channel = push ? "push" : "sms";
            String messageId = communication.notifyRefresh(s.getPartyId(), channel, payload, apps.stream().map(EcsService::appName).toList());
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("terminalId", d.getTerminalId());
            t.put("channel", channel);
            t.put("messageId", messageId);
            targets.add(t);
            log(s.getTenantId(), d.getTerminalId(), s.getImsi(), EcsService.appNames(apps), "Reconfigure",
                    messageId == null && communication.enabled() ? "notify-failed" : "notified",
                    "refresh notice by " + channel + (messageId == null ? (communication.enabled() ? " — not sent" : " — recorded, no notification seam here") : ""));
        }
        Map<String, Object> resource = new LinkedHashMap<>(toMap(s, false));
        resource.put("apps", apps);
        resource.put("payload", payload);
        resource.put("targets", targets);
        events.publish("EntitlementReconfigureRequestedEvent", "entitlementSubscriber", resource);
        return resource;
    }

    /**
     * The orchestrator replaced the line's SIM. For an eSIM transfer we asked
     * for, the transfer completes: the binding moves to the new profile's
     * ICCID and the old phone's tokens die. Other replacements are re-bound
     * by the orchestrator through {@link #upsert}.
     */
    @Transactional
    public void simReplaced(String tenantId, String serviceId, String reason, String transferId) {
        if (!"esim-transfer".equals(reason)) {
            return;
        }
        for (EntitlementSubscriber s : subscribers.findByTenantIdAndServiceId(tenantId, serviceId)) {
            for (SubscriptionTransfer t : transfers.findByTenantIdAndImsiOrderByCreatedAtDesc(tenantId, s.getImsi())) {
                if (!"profile-ready".equals(t.getStatus()) || (transferId != null && !transferId.equals(t.getId()))) {
                    continue;
                }
                t.setStatus("completed");
                t.setLastUpdate(OffsetDateTime.now());
                transfers.save(t);
                s.setIccid(t.getNewIccid());
                s.setLastUpdate(OffsetDateTime.now());
                subscribers.save(s);
                tokens.revokeAll(tenantId, s.getImsi()); // the old phone is out; the new one authenticates afresh
                events.publish("SubscriptionTransferCompletedEvent", "subscriptionTransfer", OdsaService.transferMap(t, s), tenantId);
                log(tenantId, t.getTargetTerminalId(), s.getImsi(), "eSIM for this phone", "TransferCompleted", "served",
                        "the line now runs on the new eSIM profile; the old phone's tokens were revoked");
                break;
            }
        }
    }

    /**
     * The SM-DP+ reports a profile download (SGP.22 ES2+ handleDownloadProgressInfo):
     * notification point 3 = download started/completed, 4 = installed on the
     * eUICC. The companion or transfer it belongs to follows; an installed
     * profile makes a companion ACTIVE and completes what the transfer began.
     */
    @Transactional
    public Map<String, Object> profileProgress(String tenantId, String iccid, String eid, int point, String status) {
        boolean ok = status == null || status.equalsIgnoreCase("Executed-Success");
        String state = !ok ? "failed" : point >= 4 ? "installed" : point == 3 ? "downloading" : "confirmed";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("iccid", iccid);
        out.put("profileState", state);
        for (CompanionDevice c : companions.findTop200ByTenantIdOrderByLastUpdateDesc(tenantId)) {
            if (iccid != null && iccid.equals(c.getIccid())) {
                c.setProfileState(state);
                if ("installed".equals(state)) {
                    c.setStatus(CompanionDevice.ACTIVE);
                }
                c.setLastUpdate(OffsetDateTime.now());
                companions.save(c);
                out.put("companion", c.getId());
                EntitlementSubscriber s = subscribers.findByTenantIdAndImsi(tenantId, c.getImsi()).orElse(placeholder(c.getImsi()));
                events.publish("installed".equals(state) ? "EsimProfileInstalledEvent" : "EsimProfileProgressEvent",
                        "companionDevice", OdsaService.companionMap(c, s), tenantId);
                log(tenantId, c.getCompanionTerminalId(), c.getImsi(), "companion eSIM", "ProfileDownload",
                        ok ? "served" : "notify-failed", "SM-DP+ reports the profile " + state);
            }
        }
        for (SubscriptionTransfer t : transfers.findTop200ByTenantIdOrderByCreatedAtDesc(tenantId)) {
            if (iccid != null && iccid.equals(t.getNewIccid())) {
                t.setProfileState(state);
                t.setLastUpdate(OffsetDateTime.now());
                transfers.save(t);
                out.put("transfer", t.getId());
                EntitlementSubscriber s = subscribers.findByTenantIdAndImsi(tenantId, t.getImsi()).orElse(placeholder(t.getImsi()));
                events.publish("installed".equals(state) ? "EsimProfileInstalledEvent" : "EsimProfileProgressEvent",
                        "subscriptionTransfer", OdsaService.transferMap(t, s), tenantId);
                log(tenantId, t.getTargetTerminalId(), t.getImsi(), "eSIM for this phone", "ProfileDownload",
                        ok ? "served" : "notify-failed", "SM-DP+ reports the profile " + state);
            }
        }
        return out;
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
