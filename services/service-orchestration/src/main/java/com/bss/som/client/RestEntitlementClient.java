package com.bss.som.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/** The entitlement server's BSS face ({@code PUT /tmf-api/deviceEntitlement/v1/subscriber}),
 * called with the orchestrator's machine identity. Blank base URL = no entitlement server here. */
@Component
public class RestEntitlementClient implements EntitlementClient {

    private static final Logger log = LoggerFactory.getLogger(RestEntitlementClient.class);
    private static final String SUBSCRIBER = "/tmf-api/deviceEntitlement/v1/subscriber";

    private final RestClient restClient;
    private final boolean enabled;

    public RestEntitlementClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.entitlement-base-url:}") String baseUrl) {
        this.enabled = baseUrl != null && !baseUrl.isBlank();
        this.restClient = enabled ? builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build() : null;
    }

    @Override
    public void bind(String tenantId, String partyId, String serviceId, String offeringId, String msisdn, String iccid) {
        if (!enabled) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceId", serviceId);
        if (partyId != null) body.put("partyId", partyId);
        if (offeringId != null) body.put("offeringId", offeringId);
        if (msisdn != null) body.put("msisdn", msisdn);
        if (iccid != null) body.put("iccid", iccid);
        body.put("status", "active");
        put(body, "bind service " + serviceId);
    }

    @Override
    public void changeOffering(String tenantId, String serviceId, String offeringId) {
        if (!enabled || offeringId == null) {
            return;
        }
        put(Map.of("serviceId", serviceId, "offeringId", offeringId), "plan change for service " + serviceId);
    }

    @Override
    public void rebind(String tenantId, String serviceId, String iccid) {
        if (!enabled || iccid == null) {
            return;
        }
        put(Map.of("serviceId", serviceId, "iccid", iccid, "status", "active"), "SIM re-bind for service " + serviceId);
    }

    private void put(Map<String, Object> body, String what) {
        try {
            restClient.put().uri(SUBSCRIBER).header("Content-Type", "application/json").body(body)
                    .retrieve().toBodilessEntity();
            log.info("entitlement: {} recorded", what);
        } catch (RuntimeException e) {
            // fail open: the phone learns the truth on its next check-in once the server is back
            log.warn("entitlement: {} failed ({}) — activation proceeds, reconcile later", what, e.getMessage());
        }
    }
}
