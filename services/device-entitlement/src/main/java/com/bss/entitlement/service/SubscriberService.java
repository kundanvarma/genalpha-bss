package com.bss.entitlement.service;

import com.bss.entitlement.client.AucClient;
import com.bss.entitlement.client.CommunicationClient;
import com.bss.entitlement.dto.CompanionView;
import com.bss.entitlement.dto.DeviceView;
import com.bss.entitlement.dto.EcsRequestView;
import com.bss.entitlement.dto.Es2PlusReply;
import com.bss.entitlement.dto.ReconfigureReceipt;
import com.bss.entitlement.dto.RevokeReceipt;
import com.bss.entitlement.dto.SubscriberDetail;
import com.bss.entitlement.dto.SubscriberUpsertRequest;
import com.bss.entitlement.dto.SubscriberView;
import com.bss.entitlement.dto.Ts43Block;
import com.bss.entitlement.dto.TransferView;
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
    public SubscriberView upsert(SubscriberUpsertRequest dto) {
        String tenant = tenantScope.currentTenantId();
        String imsi = dto.imsiText();
        String serviceId = dto.serviceIdText();
        String iccid = dto.iccidText();
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
                imsi = auc.identityByIccid(tenant, iccid, dto.msisdnText() != null ? dto.msisdnText() : existing == null ? null : existing.getMsisdn())
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
        boolean planChanged = SubscriberUpsertRequest.given(dto.offeringId())
                && !dto.offeringIdText().equals(s.getOfferingId());
        if (dto.has(dto.msisdn())) s.setMsisdn(dto.msisdnText());
        if (dto.has(dto.iccid())) s.setIccid(dto.iccidText());
        if (dto.has(dto.partyId())) s.setPartyId(dto.partyIdText());
        if (dto.has(dto.serviceId())) s.setServiceId(dto.serviceIdText());
        if (dto.has(dto.offeringId())) s.setOfferingId(dto.offeringIdText());
        if (SubscriberUpsertRequest.given(dto.status())) s.setStatus(requireStatus(dto.status()));
        if (SubscriberUpsertRequest.given(dto.imsProvisioned())) {
            s.setImsProvisioned(SubscriberUpsertRequest.truthy(dto.imsProvisioned()));
        }
        if (SubscriberUpsertRequest.given(dto.emergencyAddressConfirmed())) {
            s.setEmergencyAddressConfirmed(SubscriberUpsertRequest.truthy(dto.emergencyAddressConfirmed()));
        }
        if (SubscriberUpsertRequest.given(dto.termsAccepted())) {
            s.setTermsAccepted(SubscriberUpsertRequest.truthy(dto.termsAccepted()));
        }
        if (dto.has(dto.featureOverrides())) s.setFeatureOverrides(json(dto.featureOverrides()));
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
    public List<SubscriberView> list(String imsi, String partyId, String serviceId) {
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
        List<SubscriberView> out = new ArrayList<>();
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
    public SubscriberDetail entitlements(String imsi) {
        EntitlementSubscriber s = get(imsi);
        Map<String, Ts43Block> ts43 = new LinkedHashMap<>();
        for (String app : List.of("ap2003", "ap2004", "ap2005", "ap2010")) {
            ts43.put(app, decisions.decide(s, app, null));
        }
        List<CompanionView> companionList = new ArrayList<>();
        for (CompanionDevice c : companions.findByTenantIdAndImsi(s.getTenantId(), s.getImsi())) {
            companionList.add(CompanionView.of(c, s));
        }
        List<DeviceView> deviceList = new ArrayList<>();
        for (EntitlementDevice d : devices.findByTenantIdAndImsi(s.getTenantId(), s.getImsi())) {
            deviceList.add(deviceMap(d));
        }
        List<TransferView> transferList = new ArrayList<>();
        for (SubscriptionTransfer t : transfers.findByTenantIdAndImsiOrderByCreatedAtDesc(s.getTenantId(), s.getImsi())) {
            transferList.add(TransferView.of(t, s));
        }
        return new SubscriberDetail(toMap(s, false), decisions.explain(s), ts43,
                companionList, deviceList, transferList);
    }

    /** Server-initiated re-configuration (TS.43 §2.6): the ECS asks the
     * subscriber's devices to fetch their entitlements again — a push token
     * when the device registered one, else the operator's SMS path; here the
     * intent is recorded and announced, the transport is the notification seam. */
    @Transactional
    public ReconfigureReceipt reconfigure(String imsi, List<String> apps) {
        EntitlementSubscriber s = get(imsi);
        String payload;
        try {
            // the spec's own payload: an "app" list and the moment it was asked for
            payload = mapper.writeValueAsString(new ReconfigureReceipt.Payload(apps, OffsetDateTime.now().toString()));
        } catch (Exception e) {
            payload = "{\"app\":[],\"timestamp\":\"" + OffsetDateTime.now() + "\"}";
        }
        List<ReconfigureReceipt.Target> targets = new ArrayList<>();
        List<EntitlementDevice> phones = devices.findByTenantIdAndImsi(s.getTenantId(), s.getImsi());
        if (phones.isEmpty() && s.getPartyId() != null) {
            // no phone has checked in yet: the line still gets the SMS
            ReconfigureReceipt.Target t = ReconfigureReceipt.Target.line("sms",
                    communication.notifyRefresh(s.getPartyId(), "sms", payload, apps.stream().map(EcsService::appName).toList()));
            targets.add(t);
            log(s.getTenantId(), null, s.getImsi(), EcsService.appNames(apps), "Reconfigure",
                    t.messageId() == null ? "notify-failed" : "notified", "refresh notice by SMS to the line");
        }
        for (EntitlementDevice d : phones) {
            boolean push = d.getNotifToken() != null && d.getNotifAction() != null && d.getNotifAction() > 0;
            String channel = push ? "push" : "sms";
            String messageId = communication.notifyRefresh(s.getPartyId(), channel, payload, apps.stream().map(EcsService::appName).toList());
            targets.add(new ReconfigureReceipt.Target(d.getTerminalId(), channel, messageId));
            log(s.getTenantId(), d.getTerminalId(), s.getImsi(), EcsService.appNames(apps), "Reconfigure",
                    messageId == null && communication.enabled() ? "notify-failed" : "notified",
                    "refresh notice by " + channel + (messageId == null ? (communication.enabled() ? " — not sent" : " — recorded, no notification seam here") : ""));
        }
        ReconfigureReceipt resource = new ReconfigureReceipt(toMap(s, false), apps, payload, targets);
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
                events.publish("SubscriptionTransferCompletedEvent", "subscriptionTransfer", TransferView.of(t, s), tenantId);
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
    public Es2PlusReply.ProfileProgress profileProgress(String tenantId, String iccid, String eid, int point, String status) {
        boolean ok = status == null || status.equalsIgnoreCase("Executed-Success");
        String state = !ok ? "failed" : point >= 4 ? "installed" : point == 3 ? "downloading" : "confirmed";
        Es2PlusReply.ProfileProgress out = new Es2PlusReply.ProfileProgress(iccid, state);
        for (CompanionDevice c : companions.findTop200ByTenantIdOrderByLastUpdateDesc(tenantId)) {
            if (iccid != null && iccid.equals(c.getIccid())) {
                c.setProfileState(state);
                if ("installed".equals(state)) {
                    c.setStatus(CompanionDevice.ACTIVE);
                }
                c.setLastUpdate(OffsetDateTime.now());
                companions.save(c);
                out = out.withCompanion(c.getId());
                EntitlementSubscriber s = subscribers.findByTenantIdAndImsi(tenantId, c.getImsi()).orElse(placeholder(c.getImsi()));
                events.publish("installed".equals(state) ? "EsimProfileInstalledEvent" : "EsimProfileProgressEvent",
                        "companionDevice", CompanionView.of(c, s), tenantId);
                log(tenantId, c.getCompanionTerminalId(), c.getImsi(), "companion eSIM", "ProfileDownload",
                        ok ? "served" : "notify-failed", "SM-DP+ reports the profile " + state);
            }
        }
        for (SubscriptionTransfer t : transfers.findTop200ByTenantIdOrderByCreatedAtDesc(tenantId)) {
            if (iccid != null && iccid.equals(t.getNewIccid())) {
                t.setProfileState(state);
                t.setLastUpdate(OffsetDateTime.now());
                transfers.save(t);
                out = out.withTransfer(t.getId());
                EntitlementSubscriber s = subscribers.findByTenantIdAndImsi(tenantId, t.getImsi()).orElse(placeholder(t.getImsi()));
                events.publish("installed".equals(state) ? "EsimProfileInstalledEvent" : "EsimProfileProgressEvent",
                        "subscriptionTransfer", TransferView.of(t, s), tenantId);
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
    public RevokeReceipt revokeTokens(String imsi) {
        EntitlementSubscriber s = get(imsi);
        int n = tokens.revokeAll(s.getTenantId(), s.getImsi());
        return new RevokeReceipt(imsi, n);
    }

    @Transactional(readOnly = true)
    public List<DeviceView> devices() {
        List<DeviceView> out = new ArrayList<>();
        for (EntitlementDevice d : devices.findTop200ByTenantIdOrderByLastSeenAtDesc(tenantScope.currentTenantId())) {
            out.add(deviceMap(d));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<CompanionView> companions() {
        List<CompanionView> out = new ArrayList<>();
        String tenant = tenantScope.currentTenantId();
        for (CompanionDevice c : companions.findTop200ByTenantIdOrderByLastUpdateDesc(tenant)) {
            EntitlementSubscriber s = subscribers.findByTenantIdAndImsi(tenant, c.getImsi()).orElse(null);
            out.add(CompanionView.of(c, s == null ? placeholder(c.getImsi()) : s));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<EcsRequestView> requests(String imsi) {
        String tenant = tenantScope.currentTenantId();
        List<EcsRequest> rows = imsi == null ? requests.findTop200ByTenantIdOrderByCreatedAtDesc(tenant)
                : requests.findTop50ByTenantIdAndImsiOrderByCreatedAtDesc(tenant, imsi);
        List<EcsRequestView> out = new ArrayList<>();
        for (EcsRequest r : rows) {
            out.add(EcsRequestView.of(r));
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

    public SubscriberView toMap(EntitlementSubscriber s, boolean withExplanation) {
        return SubscriberView.of(s, mapper, withExplanation ? decisions.explain(s) : null);
    }

    static DeviceView deviceMap(EntitlementDevice d) {
        return DeviceView.of(d);
    }

    private static EntitlementSubscriber placeholder(String imsi) {
        EntitlementSubscriber s = new EntitlementSubscriber();
        s.setImsi(imsi);
        return s;
    }

    private static String requireStatus(com.fasterxml.jackson.databind.JsonNode v) {
        String s = SubscriberUpsertRequest.scalar(v).trim().toLowerCase();
        if (!List.of(EntitlementSubscriber.ACTIVE, EntitlementSubscriber.SUSPENDED, EntitlementSubscriber.TERMINATED).contains(s)) {
            throw new BadRequestException("status must be active, suspended or terminated");
        }
        return s;
    }

    /** Stored as the caller wrote it: a string verbatim, anything else re-serialised. */
    private static String json(com.fasterxml.jackson.databind.JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        return v.isTextual() ? v.textValue() : v.toString();
    }
}
