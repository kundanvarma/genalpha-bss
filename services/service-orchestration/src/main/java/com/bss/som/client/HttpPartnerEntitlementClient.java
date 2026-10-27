package com.bss.som.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * A partner platform over REST ({@code http}): the shape the fleet's
 * mock-partner stand-in answers and a partner gateway can be fronted to.
 * POST /activations → 201 active / 202 pending / 422 refused; GET
 * /activations/{requestKey}; DELETE /activations/{requestKey}. A read that
 * outlasts the timeout is UNKNOWN, not failed: the partner may well have
 * activated, and the sweep asks before it ever sends again.
 */
@Component
public class HttpPartnerEntitlementClient implements PartnerPlatform {

    private static final Logger log = LoggerFactory.getLogger(HttpPartnerEntitlementClient.class);

    private final PartnerSettings settings;
    private final RestClient rest;

    public HttpPartnerEntitlementClient(PartnerSettings settings,
            @Value("${bss.partner.read-timeout:3s}") Duration readTimeout) {
        this.settings = settings;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(readTimeout);
        this.rest = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String name() {
        return "http";
    }

    @Override
    public String vendor(String tenantId) {
        return "partner-gateway " + settings.forTenant(tenantId).baseUrl();
    }

    private String base(String tenantId) {
        String url = settings.forTenant(tenantId).baseUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("tenant " + tenantId + " names the http partner platform but no partner-base-url");
        }
        return url;
    }

    @Override
    public Activation activate(String tenantId, String requestKey, String offeringName, String customerPartyId) {
        try {
            PartnerAnswer answer = rest.post().uri(base(tenantId) + "/activations")
                    .body(Map.of("requestKey", requestKey, "offering", offeringName,
                            "customer", customerPartyId == null ? "" : customerPartyId))
                    .retrieve()
                    .body(PartnerAnswer.class);
            return toActivation(answer);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 422) {
                PartnerAnswer refused = e.getResponseBodyAs(PartnerAnswer.class);
                return Activation.refused(refused == null || refused.reason() == null
                        ? "the partner declined" : refused.reason());
            }
            log.warn("partner refused the request for {} with {}: treated as unknown, the sweep will ask", requestKey,
                    e.getStatusCode());
            return Activation.unknown("partner answered " + e.getStatusCode().value());
        } catch (RestClientException | IllegalStateException e) {
            // a timeout or a drop: we do not know whether it activated — that is the whole point
            log.warn("partner activation for {} did not answer in time ({}): unknown until the sweep asks",
                    requestKey, e.getMessage());
            return Activation.unknown("no answer in time");
        }
    }

    @Override
    public Activation status(String tenantId, String requestKey) {
        try {
            return toActivation(rest.get().uri(base(tenantId) + "/activations/{key}", requestKey)
                    .retrieve().body(PartnerAnswer.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Activation.unknown("never received");
        } catch (RestClientException | IllegalStateException e) {
            return Activation.pending();   // could not ask: keep waiting, never assume
        }
    }

    @Override
    public void deactivate(String tenantId, String requestKey, String activationCode, String customerPartyId) {
        rest.delete().uri(base(tenantId) + "/activations/{key}", requestKey)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    if (res.getStatusCode().value() != 404) {   // already gone is gone
                        throw new IllegalStateException("partner could not deactivate (" + res.getStatusCode().value() + ")");
                    }
                })
                .toBodilessEntity();
    }

    private static Activation toActivation(PartnerAnswer answer) {
        if (answer == null || answer.state() == null) {
            return Activation.unknown("empty answer");
        }
        return switch (answer.state()) {
            case "active" -> Activation.active(answer.code());
            case "refused" -> Activation.refused(answer.reason() == null ? "the partner declined" : answer.reason());
            case "pending" -> Activation.pending();
            default -> Activation.unknown("partner state " + answer.state());
        };
    }

    /** The partner's answer, as much of it as we read. */
    record PartnerAnswer(String requestKey, String state, String code, String reason) {
    }
}
