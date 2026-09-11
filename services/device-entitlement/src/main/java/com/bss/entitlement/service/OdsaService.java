package com.bss.entitlement.service;

import com.bss.entitlement.entity.CompanionDevice;
import com.bss.entitlement.entity.EntitlementSubscriber;
import com.bss.entitlement.entity.SubscriptionTransfer;
import com.bss.entitlement.events.DomainEventPublisher;
import com.bss.entitlement.repository.CompanionDeviceRepository;
import com.bss.entitlement.repository.SubscriptionTransferRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * On-Device Service Activation (TS.43 §6): the phone's own client activates an
 * eSIM — a COMPANION (watch, tablet: ap2006) sharing the primary line, or a
 * PRIMARY eSIM (ap2009: a new phone, a transfer from the old one). Operations:
 * CheckEligibility, ManageSubscription (SUBSCRIBE / UNSUBSCRIBE / TRANSFER…),
 * ManageService, AcquireConfiguration. The plan decides eligibility; the
 * profile download is an SM-DP+ activation code (SGP.22); the line-side swap
 * for a transfer is the orchestrator's job — announced on the event bus.
 *
 * OperationResult: 1 SUCCESS · 100 ERROR_GENERAL · 101 ERROR_INVALID_OPERATION
 * · 102 ERROR_INVALID_PARAMETER · 103 ERROR_INVALID_MSISDN · 104 ERROR_INVALID_ICCID.
 * SubscriptionResult: 1 CONTINUE_TO_WEBSHEET · 2 DOWNLOAD_PROFILE · 3 DONE
 * · 4 DELAYED_DOWNLOAD · 5 DISMISS · 6 DELETE_PROFILE_IN_USE.
 */
@Service
public class OdsaService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CompanionDeviceRepository companions;
    private final SubscriptionTransferRepository transfers;
    private final EntitlementDecisionService decisions;
    private final DomainEventPublisher events;
    private final String smdp;
    private final String publicBaseUrl;

    public OdsaService(CompanionDeviceRepository companions, SubscriptionTransferRepository transfers,
            EntitlementDecisionService decisions, DomainEventPublisher events,
            @Value("${bss.entitlement.smdp-address:rsp.example.net}") String smdp,
            @Value("${bss.entitlement.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.companions = companions;
        this.transfers = transfers;
        this.decisions = decisions;
        this.events = events;
        this.smdp = smdp;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    /** Handle one ODSA app request. Returns the application block. */
    @Transactional
    public Map<String, Object> handle(EntitlementSubscriber s, String app, Map<String, String> p) {
        String operation = p.getOrDefault("operation", "AcquireConfiguration");
        Map<String, Boolean> f = decisions.features(s);
        boolean live = EntitlementSubscriber.ACTIVE.equals(s.getStatus());
        Map<String, Object> out = new LinkedHashMap<>();
        if ("ap2006".equals(app)) {
            boolean eligible = live && f.getOrDefault("companionesim", false);
            switch (operation) {
                case "CheckEligibility" -> {
                    out.put("CompanionAppEligibility", eligible ? "1" : "0");
                    out.put("CompanionDeviceServices", "SharedNumber");
                    if (!eligible) {
                        out.put("NotEnabledURL", publicBaseUrl + "/ts43/flow/not-enabled");
                        out.put("NotEnabledUserData", "reason=" + (live ? "plan" : "line"));
                    }
                    out.put("OperationResult", "1");
                }
                case "ManageSubscription" -> manageCompanion(s, p, eligible, out);
                case "ManageService" -> {
                    CompanionDevice c = companion(s, p.get("companion_terminal_id"));
                    if (c == null) {
                        out.put("OperationResult", "104");
                    } else {
                        String action = p.getOrDefault("companion_service_action", "1"); // 1 activate, 2 deactivate
                        c.setStatus("2".equals(action) ? CompanionDevice.SUBSCRIBED : CompanionDevice.ACTIVE);
                        c.setLastUpdate(OffsetDateTime.now());
                        companions.save(c);
                        out.put("ServiceStatus", "2".equals(action) ? "3" : "1"); // 1 ACTIVATED, 3 DEACTIVATED
                        out.put("OperationResult", "1");
                    }
                }
                case "AcquireConfiguration" -> {
                    List<Map<String, Object>> configs = new ArrayList<>();
                    for (CompanionDevice c : companions.findByTenantIdAndImsi(s.getTenantId(), s.getImsi())) {
                        if (p.get("companion_terminal_id") != null && !p.get("companion_terminal_id").equals(c.getCompanionTerminalId())) {
                            continue;
                        }
                        Map<String, Object> cfg = new LinkedHashMap<>();
                        cfg.put("ICCID", c.getIccid());
                        cfg.put("CompanionDeviceService", "SharedNumber");
                        cfg.put("ServiceStatus", serviceStatus(c.getStatus()));
                        cfg.put("CompanionTerminalId", c.getCompanionTerminalId());
                        configs.add(Map.of("CompanionConfiguration", cfg));
                    }
                    out.put("CompanionConfigurations", configs);
                    out.put("OperationResult", "1");
                }
                default -> out.put("OperationResult", "101");
            }
            return out;
        }
        if ("ap2009".equals(app)) {
            boolean transferable = live && f.getOrDefault("esimtransfer", false);
            switch (operation) {
                case "CheckEligibility" -> {
                    out.put("PrimaryAppEligibility", live ? "1" : "0");
                    out.put("OperationResult", "1");
                }
                case "ManageSubscription" -> {
                    String type = p.getOrDefault("operation_type", "");
                    if ("3".equals(type)) { // TRANSFER SUBSCRIPTION
                        if (!transferable) {
                            out.put("SubscriptionResult", "5"); // DISMISS
                            out.put("MSG", Map.of("title", "eSIM transfer", "message",
                                    "This plan does not allow moving the eSIM by yourself. Please contact us."));
                            out.put("OperationResult", "1");
                        } else {
                            SubscriptionTransfer t = new SubscriptionTransfer();
                            t.setId(UUID.randomUUID().toString());
                            t.setTenantId(s.getTenantId());
                            t.setImsi(s.getImsi());
                            t.setOldTerminalId(p.get("old_terminal_id"));
                            t.setTargetTerminalId(p.getOrDefault("target_terminal_id", p.get("terminal_id")));
                            t.setTargetEid(p.getOrDefault("target_terminal_eid", p.get("terminal_eid")));
                            t.setNewIccid(mintIccid());
                            t.setActivationCode(activationCode(t.getNewIccid()));
                            t.setStatus("profile-ready");
                            t.setCreatedAt(OffsetDateTime.now());
                            t.setLastUpdate(t.getCreatedAt());
                            transfers.save(t);
                            events.publish("SubscriptionTransferRequestedEvent", "subscriptionTransfer", transferMap(t, s));
                            out.put("SubscriptionResult", "2"); // DOWNLOAD PROFILE
                            out.put("DownloadInfo", Map.of("ProfileIccid", t.getNewIccid(),
                                    "ProfileActivationCode", Base64.getEncoder().encodeToString(t.getActivationCode().getBytes()),
                                    "ProfileSmdpAddress", smdp));
                            out.put("OperationResult", "1");
                        }
                    } else if ("0".equals(type)) { // SUBSCRIBE: a new primary eSIM needs a sale — websheet
                        out.put("SubscriptionResult", "1");
                        out.put("SubscriptionServiceURL", publicBaseUrl + "/ts43/flow/subscribe");
                        out.put("SubscriptionServiceUserData", "imsi=" + s.getImsi());
                        out.put("OperationResult", "1");
                    } else {
                        out.put("OperationResult", "101");
                    }
                }
                case "AcquireConfiguration" -> {
                    Map<String, Object> cfg = new LinkedHashMap<>();
                    cfg.put("ICCID", s.getIccid());
                    cfg.put("ServiceStatus", live ? "1" : "3");
                    cfg.put("PolicyEnabled", "0");
                    out.put("PrimaryConfiguration", cfg);
                    out.put("OperationResult", "1");
                }
                default -> out.put("OperationResult", "101");
            }
            return out;
        }
        out.put("OperationResult", "101");
        return out;
    }

    private void manageCompanion(EntitlementSubscriber s, Map<String, String> p, boolean eligible, Map<String, Object> out) {
        String type = p.getOrDefault("operation_type", "0");
        String terminalId = p.get("companion_terminal_id");
        if (terminalId == null || terminalId.isBlank()) {
            out.put("OperationResult", "102");
            return;
        }
        if ("1".equals(type)) { // UNSUBSCRIBE
            CompanionDevice c = companion(s, terminalId);
            if (c != null) {
                c.setStatus(CompanionDevice.UNSUBSCRIBED);
                c.setLastUpdate(OffsetDateTime.now());
                companions.save(c);
                events.publish("CompanionDeviceUnsubscribedEvent", "companionDevice", companionMap(c, s));
            }
            out.put("SubscriptionResult", "3"); // DONE
            out.put("OperationResult", "1");
            return;
        }
        if (!eligible) {
            out.put("SubscriptionResult", "5"); // DISMISS
            out.put("MSG", Map.of("title", "Companion device", "message", "This plan does not include a companion eSIM."));
            out.put("OperationResult", "1");
            return;
        }
        CompanionDevice c = companion(s, terminalId);
        if (c == null) {
            c = new CompanionDevice();
            c.setId(UUID.randomUUID().toString());
            c.setTenantId(s.getTenantId());
            c.setImsi(s.getImsi());
            c.setCompanionTerminalId(terminalId);
            c.setCreatedAt(OffsetDateTime.now());
        }
        c.setEid(p.getOrDefault("companion_terminal_eid", c.getEid()));
        c.setVendor(p.getOrDefault("companion_terminal_vendor", c.getVendor()));
        c.setModel(p.getOrDefault("companion_terminal_model", c.getModel()));
        if (c.getIccid() == null) {
            c.setIccid(mintIccid());
            c.setActivationCode(activationCode(c.getIccid()));
        }
        c.setStatus(CompanionDevice.SUBSCRIBED);
        c.setLastUpdate(OffsetDateTime.now());
        companions.save(c);
        events.publish("CompanionDeviceSubscribedEvent", "companionDevice", companionMap(c, s));
        out.put("SubscriptionResult", "2"); // DOWNLOAD PROFILE
        out.put("DownloadInfo", Map.of("ProfileIccid", c.getIccid(),
                "ProfileActivationCode", Base64.getEncoder().encodeToString(c.getActivationCode().getBytes()),
                "ProfileSmdpAddress", smdp));
        out.put("OperationResult", "1");
    }

    private CompanionDevice companion(EntitlementSubscriber s, String terminalId) {
        return terminalId == null ? null
                : companions.findByTenantIdAndImsiAndCompanionTerminalId(s.getTenantId(), s.getImsi(), terminalId).orElse(null);
    }

    private static String serviceStatus(String status) {
        return switch (status) {
            case CompanionDevice.ACTIVE -> "1";       // ACTIVATED
            case CompanionDevice.SUBSCRIBED -> "2";   // ACTIVATING
            case CompanionDevice.UNSUBSCRIBED -> "3"; // DEACTIVATED
            default -> "4";                            // DEACTIVATED_NO_REUSE
        };
    }

    /** A demo ICCID (89 = telecom, then random) — the SM-DP+ owns the real one. */
    private static String mintIccid() {
        StringBuilder sb = new StringBuilder("8947");
        for (int i = 0; i < 15; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    /** SGP.22 activation code: {@code LPA:1$<SM-DP+ address>$<matching id>}. */
    private String activationCode(String iccid) {
        return "LPA:1$" + smdp + "$" + iccid.substring(iccid.length() - 8).toUpperCase() + "-" + Integer.toHexString(RANDOM.nextInt(0xFFFFFF)).toUpperCase();
    }

    public static Map<String, Object> companionMap(CompanionDevice c, EntitlementSubscriber s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("imsi", c.getImsi());
        m.put("msisdn", s.getMsisdn());
        m.put("partyId", s.getPartyId());
        m.put("serviceId", s.getServiceId());
        m.put("companionTerminalId", c.getCompanionTerminalId());
        m.put("eid", c.getEid());
        m.put("iccid", c.getIccid());
        m.put("vendor", c.getVendor());
        m.put("model", c.getModel());
        m.put("status", c.getStatus());
        m.put("activationCode", c.getActivationCode());
        m.put("createdAt", c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
        m.put("lastUpdate", c.getLastUpdate() == null ? null : c.getLastUpdate().toString());
        return m;
    }

    public static Map<String, Object> transferMap(SubscriptionTransfer t, EntitlementSubscriber s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("imsi", t.getImsi());
        m.put("msisdn", s.getMsisdn());
        m.put("partyId", s.getPartyId());
        m.put("serviceId", s.getServiceId());
        m.put("oldTerminalId", t.getOldTerminalId());
        m.put("targetTerminalId", t.getTargetTerminalId());
        m.put("targetEid", t.getTargetEid());
        m.put("newIccid", t.getNewIccid());
        m.put("status", t.getStatus());
        m.put("activationCode", t.getActivationCode());
        m.put("createdAt", t.getCreatedAt() == null ? null : t.getCreatedAt().toString());
        return m;
    }
}
