package com.bss.basemigration.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TMF651 view of commitments: an in-binding customer is treated per the
 * plan's eligibility (defer, exclude, or free exit) — so this lookup
 * FAILS CLOSED. If the agreement component is unreachable during
 * discovery, discovery aborts rather than migrating a bound customer
 * whose binding we could not see. (The ordering side fails open for the
 * customer's own plan change; this engine acts ON customers, so the
 * risk calculus inverts.)
 */
@Component
public class AgreementClient {

    private final RestClient rest;

    public AgreementClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.agreement-base-url:http://localhost:8098}") String baseUrl) {
        this.rest = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    /**
     * The party's unexpired commitment end for one offering, if any.
     * TMF651 wire shape: agreementPeriod {startDateTime, endDateTime},
     * agreementItem[].productOffering.id.
     */
    public Optional<OffsetDateTime> commitmentEnd(String partyId, String offeringId, OffsetDateTime now) {
        List<Map<String, Object>> agreements = rest.get()
                .uri("/tmf-api/agreementManagement/v4/agreement?relatedPartyId={p}&status=active&limit=100",
                        partyId)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() { });
        OffsetDateTime latest = null;
        for (Map<String, Object> agreement : agreements == null ? List.<Map<String, Object>>of() : agreements) {
            Object endRaw = agreement.get("agreementPeriod") instanceof Map<?, ?> period
                    ? period.get("endDateTime") : agreement.get("periodEnd");
            OffsetDateTime end;
            try {
                end = endRaw == null ? null : OffsetDateTime.parse(String.valueOf(endRaw));
            } catch (DateTimeParseException e) {
                continue;
            }
            if (end == null || !end.isAfter(now)
                    || !(agreement.get("agreementItem") instanceof List<?> items)) {
                continue;
            }
            for (Object it : items) {
                if (it instanceof Map<?, ?> m && m.get("productOffering") instanceof Map<?, ?> off
                        && offeringId.equals(String.valueOf(off.get("id")))
                        && (latest == null || end.isAfter(latest))) {
                    latest = end;
                }
            }
        }
        return Optional.ofNullable(latest);
    }
}
