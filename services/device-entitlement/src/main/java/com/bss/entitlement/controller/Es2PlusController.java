package com.bss.entitlement.controller;

import com.bss.entitlement.api.ApiConstants;
import com.bss.entitlement.security.TenantContext;
import com.bss.entitlement.service.SubscriberService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The SM-DP+ → operator notification (SGP.22 ES2+ §5.3.5
 * {@code handleDownloadProgressInfo}): the profile server tells us how far a
 * download got. One door per tenant — the SM-DP+ knows the operator by the
 * requester identifier it was given, which is the path segment here. Under
 * {@code /ts43/**} so the gateway already routes it; production fronts it
 * with the mutual-TLS the SM-DP+ contract requires.
 */
@RestController
@RequestMapping(ApiConstants.TS43_PATH + "/es2plus")
public class Es2PlusController {

    private final SubscriberService subscribers;

    public Es2PlusController(SubscriberService subscribers) {
        this.subscribers = subscribers;
    }

    @PostMapping("/{tenantId}/handleDownloadProgressInfo")
    public Map<String, Object> progress(@PathVariable("tenantId") String tenantId, @RequestBody Map<String, Object> body) {
        String iccid = str(body.get("iccid"));
        String eid = str(body.get("eid"));
        int point = 0;
        try {
            point = Integer.parseInt(String.valueOf(body.getOrDefault("notificationPointId", "0")));
        } catch (NumberFormatException ignore) { /* stays 0 */ }
        String status = null;
        if (body.get("notificationPointStatus") instanceof Map<?, ?> nps && nps.get("status") != null) {
            status = String.valueOf(nps.get("status"));
        }
        Map<String, Object> result;
        try (TenantContext ignored = TenantContext.actAs(tenantId)) {
            result = subscribers.profileProgress(tenantId, iccid, eid, point, status);
        }
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("header", Map.of("functionExecutionStatus", Map.of("status", "Executed-Success")));
        reply.put("applied", result);
        return reply;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
