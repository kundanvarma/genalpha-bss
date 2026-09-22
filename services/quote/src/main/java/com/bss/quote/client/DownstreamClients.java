package com.bss.quote.client;

import com.bss.quote.dto.HandoffBodies.AgreementRequest;
import com.bss.quote.dto.HandoffBodies.NarrativeContext;
import com.bss.quote.dto.HandoffBodies.ProductOrderRequest;
import com.bss.quote.dto.LeadSignal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What quoting needs to see: the intent's proposal (OSS), the catalog's
 * prices, the usage component's token allowances — and one write: the
 * accepted quote becomes a product order. All calls run under the acting
 * tenant's machine identity. Foreign documents come back as {@link JsonNode}
 * — quote reads them, never re-shapes them; the CDP's lead signal is the
 * one small typed answer.
 */
@Component
public class DownstreamClients {

    private final RestClient som;
    private final RestClient catalog;
    private final RestClient usage;
    private final RestClient ordering;
    private final RestClient intelligence;
    private final RestClient agreement;
    private final RestClient insight;
    private final ObjectMapper objectMapper;

    public DownstreamClients(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            ObjectMapper objectMapper,
            @Value("${bss.downstream.som-base-url:http://localhost:8104}") String somBaseUrl,
            @Value("${bss.downstream.catalog-base-url:http://localhost:8081}") String catalogBaseUrl,
            @Value("${bss.downstream.usage-base-url:http://localhost:8097}") String usageBaseUrl,
            @Value("${bss.downstream.ordering-base-url:http://localhost:8082}") String orderingBaseUrl,
            @Value("${bss.downstream.intelligence-base-url:http://localhost:8109}") String intelligenceBaseUrl,
            @Value("${bss.downstream.agreement-base-url:http://localhost:8098}") String agreementBaseUrl,
            @Value("${bss.downstream.insight-base-url:http://localhost:8110}") String insightBaseUrl) {
        this.som = builder.baseUrl(somBaseUrl).requestInterceptor(tokenInterceptor).build();
        this.catalog = builder.baseUrl(catalogBaseUrl).requestInterceptor(tokenInterceptor).build();
        this.usage = builder.baseUrl(usageBaseUrl).requestInterceptor(tokenInterceptor).build();
        this.ordering = builder.baseUrl(orderingBaseUrl).requestInterceptor(tokenInterceptor).build();
        this.intelligence = builder.baseUrl(intelligenceBaseUrl).requestInterceptor(tokenInterceptor).build();
        this.agreement = builder.baseUrl(agreementBaseUrl).requestInterceptor(tokenInterceptor).build();
        this.insight = builder.baseUrl(insightBaseUrl).requestInterceptor(tokenInterceptor).build();
        this.objectMapper = objectMapper;
    }

    /** The CDP's lead signal for an email: {knownProspect, engagement, engaged}.
     *  Fail-soft — an unreachable CDP just means no engagement signal. */
    public LeadSignal leadSignal(String email) {
        try {
            String body = insight.get()
                    .uri(uri -> uri.path("/insight/v1/leadSignal").queryParam("email", email).build())
                    .retrieve().body(String.class);
            return objectMapper.readValue(body, LeadSignal.class);
        } catch (RestClientException e) {
            return LeadSignal.NONE;
        } catch (Exception e) {
            throw new IllegalStateException("downstream answer unreadable", e);
        }
    }

    /** The party's CDP segments (its trait values) — for CPQ segment pricing.
     *  Fail-soft — no CDP just means list/volume pricing. */
    public Set<String> partySegments(String partyId) {
        if (partyId == null) return Set.of();
        try {
            JsonNode body = parseObject(insight.get()
                    .uri(uri -> uri.path("/insight/v1/partySegments").queryParam("partyId", partyId).build())
                    .retrieve().body(String.class));
            JsonNode segs = body.path("segments");
            if (segs.isArray()) {
                Set<String> out = new LinkedHashSet<>();
                for (JsonNode s : segs) out.add(s.asText());
                return out;
            }
        } catch (RestClientException e) {
            // fall through
        }
        return Set.of();
    }

    /** The TMF921 intent with its feasibility report — the OSS's document. */
    public JsonNode intent(String intentId) {
        return parseObject(som.get().uri("/tmf-api/intentManagement/v4/intent/" + intentId)
                .retrieve().body(String.class));
    }

    /** The catalog's offerings (an array; empty when unreadable). */
    public JsonNode offerings() {
        return parseList(catalog.get()
                .uri("/tmf-api/productCatalogManagement/v4/productOffering?limit=100")
                .retrieve().body(String.class));
    }

    public JsonNode offeringPrice(String priceId) {
        return parseObject(catalog.get()
                .uri("/tmf-api/productCatalogManagement/v4/productOfferingPrice/" + priceId)
                .retrieve().body(String.class));
    }

    /** The usage component's token allowances (an array; empty when unreadable). */
    public JsonNode allowances() {
        return parseList(usage.get()
                .uri("/tmf-api/usageManagement/v4/usageAllowance?limit=100")
                .retrieve().body(String.class));
    }

    public JsonNode placeOrder(ProductOrderRequest order) {
        return parseObject(ordering.post()
                .uri("/tmf-api/productOrderingManagement/v4/productOrder")
                .header("Content-Type", "application/json")
                .body(order).retrieve().body(String.class));
    }

    /** The accepted quote also becomes a TMF651 agreement (the contract). */
    public JsonNode createAgreement(AgreementRequest agreementBody) {
        return parseObject(agreement.post()
                .uri("/tmf-api/agreementManagement/v4/agreement")
                .header("Content-Type", "application/json")
                .body(agreementBody).retrieve().body(String.class));
    }

    /** Fail-soft: a quote without prose is still a quote. */
    public String quoteNarrative(NarrativeContext context) {
        try {
            JsonNode reply = parseObject(intelligence.post()
                    .uri("/ai/v1/quoteNarrative")
                    .header("Content-Type", "application/json")
                    .body(context).retrieve().body(String.class));
            JsonNode narrative = reply.path("narrative");
            return narrative.isMissingNode() || narrative.isNull() ? null : narrative.asText();
        } catch (RestClientException e) {
            return null;
        }
    }

    private JsonNode parseObject(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("downstream answer unreadable", e);
        }
    }

    private JsonNode parseList(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            return node != null && node.isArray() ? node : objectMapper.createArrayNode();
        } catch (Exception e) {
            return objectMapper.createArrayNode();
        }
    }
}
