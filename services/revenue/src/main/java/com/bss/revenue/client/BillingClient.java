package com.bss.revenue.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Machine reads under the acting tenant's own identity (bss-revenue,
 * billing:read + campaign:read): the bill's rate lines are the journal's
 * raw material; the loyalty liability is a reconciliation control number.
 */
@Component
public class BillingClient {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient billing;
    private final RestClient loyalty;
    private final RestClient agreement;

    public BillingClient(RestClient.Builder builder, MachineTokenInterceptor machineToken,
            @Value("${bss.downstream.billing-base-url}") String billingBase,
            @Value("${bss.downstream.loyalty-base-url}") String loyaltyBase,
            @Value("${bss.downstream.agreement-base-url}") String agreementBase) {
        this.billing = builder.baseUrl(billingBase).requestInterceptor(machineToken).build();
        this.loyalty = RestClient.builder().baseUrl(loyaltyBase).requestInterceptor(machineToken).build();
        this.agreement = RestClient.builder().baseUrl(agreementBase).requestInterceptor(machineToken).build();
    }

    public Map<String, Object> bill(String billId) {
        return billing.get().uri("/tmf-api/customerBillManagement/v4/customerBill/{id}", billId)
                .retrieve().body(MAP);
    }

    public List<Map<String, Object>> ratesOf(String billId) {
        return billing.get()
                .uri("/tmf-api/customerBillManagement/v4/customerBill/{id}/appliedCustomerBillingRate", billId)
                .retrieve().body(LIST);
    }

    /** The TMF651 commitments — rev-rec's obligation timeline. */
    public List<Map<String, Object>> agreements() {
        List<Map<String, Object>> out = agreement.get()
                .uri("/tmf-api/agreementManagement/v4/agreement?limit=200").retrieve().body(LIST);
        return out == null ? List.of() : out;
    }

    /** Fail-soft: no loyalty component (or no program) is not an error. */
    public Long loyaltyPointsLiability() {
        try {
            Map<String, Object> body = loyalty.get()
                    .uri("/tmf-api/loyaltyManagement/v4/liability").retrieve().body(MAP);
            return body == null || body.get("outstandingPoints") == null ? null
                    : Long.valueOf(String.valueOf(body.get("outstandingPoints")));
        } catch (Exception e) {
            return null;
        }
    }
}
