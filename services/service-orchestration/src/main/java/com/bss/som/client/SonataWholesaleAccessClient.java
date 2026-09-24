package com.bss.som.client;

import com.bss.som.entity.WholesaleAccessOrder;
import com.bss.som.security.WholesaleDoorAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.bss.som.mapper.Wire.idOf;

/**
 * The real (HTTP) access-seeker adapter: places the order on the owner's MEF LSO
 * Sonata Service Ordering API and returns ACKNOWLEDGED — the line is not live yet.
 * The owner activates on its own clock and notifies our callback, which flips the
 * access order active and completes the retail line. Enabled with
 * bss.wholesale.oss=sonata; the mock activates instantly instead.
 */
@Component
@ConditionalOnProperty(name = "bss.wholesale.oss", havingValue = "sonata")
public class SonataWholesaleAccessClient implements WholesaleAccessClient {

    private static final Logger log = LoggerFactory.getLogger(SonataWholesaleAccessClient.class);
    private static final String ORDER = "/mefApi/serviceOrdering/v1/serviceOrder";

    private final RestClient.Builder builder;
    private final WholesaleDoorAuth auth;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> ownerUrls;
    private final String callbackBase;

    /** Owners that are OTHER OPERATORS on our own platform — the order rides
     *  X-Tenant-Id to their provider tenant instead of an external host. */
    private final Map<String, String> ownerTenants;

    public SonataWholesaleAccessClient(RestClient.Builder builder, WholesaleDoorAuth auth,
            @Value("${bss.wholesale.sonata.nordaccess:http://localhost:8134}") String nordUrl,
            @Value("${bss.wholesale.sonata.fjordfiber:http://localhost:8135}") String fjordUrl,
            @Value("${bss.wholesale.sonata.novafibre:http://localhost:8104}") String novaUrl,
            @Value("${bss.wholesale.sonata.novafibre-tenant:nova}") String novaTenant,
            @Value("${bss.wholesale.callback-base:http://localhost:8080}") String callbackBase) {
        this.builder = builder;
        this.auth = auth;
        this.ownerUrls = Map.of("NORDACCESS", nordUrl, "FJORDFIBER", fjordUrl, "NOVAFIBRE", novaUrl);
        // NOVAFIBRE is nova, another operator on this very platform: the same
        // Sonata face, reached cross-tenant via the header rather than a host.
        this.ownerTenants = Map.of("NOVAFIBRE", novaTenant);
        this.callbackBase = callbackBase;
    }

    @Override
    public AccessOrderResult order(String accessOwner, String accessLayer, Integer bandwidthMbps,
            String postCode, String serviceId, String buyerRef) {
        String base = ownerUrls.get(accessOwner);
        if (base == null) {
            log.warn("no Sonata endpoint configured for owner {} — cannot order upstream", accessOwner);
            return new AccessOrderResult(null, WholesaleAccessOrder.FAILED);
        }
        // The callback carries its own credential: a token bound to this order and
        // derived from OUR tenant's wholesale secret. The owner's OSS posts to the
        // URL verbatim, as it always has — it never learns there is a credential in
        // it — and a leaked or guessed order UUID no longer activates anything.
        String seekerTenant = com.bss.som.security.TenantContext.current();
        String callbackUrl = callbackBase
                + "/tmf-api/serviceOrdering/v4/wholesaleAccessOrder/" + buyerRef + "/notification";
        if (auth.configured(seekerTenant)) {
            callbackUrl = callbackUrl + "/" + auth.callbackToken(seekerTenant, buyerRef);
        } else {
            log.warn("no wholesale-order-secret for tenant {} — the activation callback for {} carries no "
                    + "credential and will be refused", seekerTenant, buyerRef);
        }
        Map<String, Object> service = new LinkedHashMap<>();
        service.put("serviceCharacteristic", List.of(
                Map.of("name", "accessLayer", "value", accessLayer == null ? "" : accessLayer),
                Map.of("name", "bandwidthMbps", "value", bandwidthMbps),
                Map.of("name", "postCode", "value", postCode)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("externalId", buyerRef);
        body.put("callbackUrl", callbackUrl);
        // who we are — the owner bills this operator for the access it sells us
        body.put("buyerId", seekerTenant);
        body.put("serviceOrderItem", List.of(Map.of("action", "add", "service", service)));
        String providerTenant = ownerTenants.get(accessOwner);
        try {
            RestClient client = builder.clone().baseUrl(base).build();
            // an ON-PLATFORM owner is another operator's Sonata face on this very
            // deployment: reached cross-tenant by the header AND signed with that
            // operator's own wholesale secret, which is what makes the header safe
            // to believe at the far end. An EXTERNAL owner has its own front door
            // and its own credential scheme; we send it what we always sent.
            // serialise ONCE: the bytes we sign must be the bytes on the wire
            String json = mapper.writeValueAsString(body);
            String signature = providerTenant == null ? null : sign(providerTenant, json);
            Map<String, Object> resp = client.post().uri(ORDER)
                    .header("Content-Type", "application/json")
                    .headers(h -> {
                        if (providerTenant != null) {
                            h.set("X-Tenant-Id", providerTenant);
                        }
                        if (signature != null) {
                            h.set(WholesaleDoorAuth.SIGNATURE_HEADER, signature);
                        }
                    })
                    .body(json).retrieve().body(Map.class);
            String id = idOf(resp); // no id = no external reference, not one called "null"
            log.info("Sonata: {} acknowledged access order {} (buyerRef {})", accessOwner, id, buyerRef);
            return new AccessOrderResult(id, WholesaleAccessOrder.IN_PROGRESS);
        } catch (RestClientException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Sonata order to {} failed: {}", accessOwner, e.getMessage());
            return new AccessOrderResult(null, WholesaleAccessOrder.FAILED);
        }
    }

    /** Sign exactly the bytes that go on the wire, with the provider's own secret. */
    private String sign(String providerTenant, String json) {
        try {
            return auth.signature(providerTenant, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            log.warn("cannot sign the wholesale order for provider tenant {} — it will be refused", providerTenant);
            return null;
        }
    }
}
