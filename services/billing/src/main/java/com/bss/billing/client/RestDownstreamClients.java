package com.bss.billing.client;

import com.bss.billing.exception.DownstreamException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Configuration
public class RestDownstreamClients {

    private RestClient client(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor, String baseUrl) {
        // JDK HttpClient factory: HttpURLConnection cannot send PATCH.
        return builder.baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory())
                .requestInterceptor(tokenInterceptor)
                .build();
    }

    @Bean
    DownstreamClients.InventoryClient inventoryClient(RestClient.Builder builder,
            MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.inventory-base-url}") String baseUrl) {
        RestClient rest = client(builder, tokenInterceptor, baseUrl);
        return new DownstreamClients.InventoryClient() {
            @Override
            public List<Map<String, Object>> productsOf(String partyId) {
                try {
                    List<Map<String, Object>> page = rest.get()
                            .uri("/tmf-api/productInventory/v4/product?relatedPartyId={p}&limit=100", partyId)
                            .retrieve().body(List.class);
                    return page == null ? List.of() : page;
                } catch (Exception e) {
                    return List.of();
                }
            }

            @Override
            public List<Map<String, Object>> activeProducts() {
            // active products AND cancelled ones — a line ceased mid-period
            // still owes its days; the run's date-math decides what counts
            List<Map<String, Object>> all = new ArrayList<>();
            for (String status : List.of("active", "cancelled")) {
            int offset = 0;
            while (true) {
                try {
                    List<Map<String, Object>> page = rest.get()
                            .uri("/tmf-api/productInventory/v4/product?status={s}&offset={o}&limit=100",
                                    status, offset)
                            .retrieve()
                            .body(new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {
                            });
                    all.addAll(page);
                    if (page.size() < 100) {
                        break;
                    }
                    offset += 100;
                } catch (RestClientException e) {
                    throw new DownstreamException("product-inventory is unreachable", e);
                }
            }
            }
            return all;
            }
        };
    }

    @Bean
    DownstreamClients.CatalogClient catalogClient(RestClient.Builder builder,
            MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.catalog-base-url}") String baseUrl) {
        RestClient rest = client(builder, tokenInterceptor, baseUrl);
        return new DownstreamClients.CatalogClient() {
            @Override
            public List<Map<String, Object>> allOfferings() {
                List<Map<String, Object>> all = new java.util.ArrayList<>();
                for (int offset = 0; offset < 5000; offset += 100) {
                    try {
                        List<Map<String, Object>> page = rest.get()
                                .uri("/tmf-api/productCatalogManagement/v4/productOffering?limit=100&offset={o}",
                                        offset)
                                .retrieve().body(List.class);
                        if (page == null || page.isEmpty()) {
                            break;
                        }
                        all.addAll(page);
                        if (page.size() < 100) {
                            break;
                        }
                    } catch (Exception e) {
                        break;
                    }
                }
                return all;
            }

            public List<Map<String, Object>> offeringsByName(String name) {
                try {
                    List<Map<String, Object>> page = rest.get()
                            .uri("/tmf-api/productCatalogManagement/v4/productOffering?name={n}", name)
                            .retrieve().body(List.class);
                    return page == null ? List.of() : page;
                } catch (Exception e) {
                    return List.of();
                }
            }

            @SuppressWarnings("unchecked")
            private Map<String, Object> get(String path, String id) {
                try {
                    return rest.get().uri("/tmf-api/productCatalogManagement/v4/" + path + "/" + id)
                            .retrieve().body(Map.class);
                } catch (HttpClientErrorException.NotFound e) {
                    return null;
                } catch (RestClientException e) {
                    throw new DownstreamException("product-catalog is unreachable", e);
                }
            }

            @Override
            public Map<String, Object> offering(String id) {
                return get("productOffering", id);
            }

            @Override
            public Map<String, Object> price(String id) {
                return get("productOfferingPrice", id);
            }
        };
    }

    @Bean
    DownstreamClients.UsageClient usageClient(RestClient.Builder builder,
            MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.usage-base-url}") String baseUrl) {
        RestClient rest = client(builder, tokenInterceptor, baseUrl);
        return new DownstreamClients.UsageClient() {
            @Override
            public List<Map<String, Object>> rateForParty(String ownerPartyId, String periodStart, String periodEnd) {
                try {
                    return rest.post().uri("/tmf-api/usageManagement/v4/rateUsage")
                            .header("Content-Type", "application/json")
                            .body(Map.of("relatedPartyId", ownerPartyId,
                                    "periodStart", periodStart, "periodEnd", periodEnd))
                            .retrieve()
                            .body(new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {
                            });
                } catch (RestClientException e) {
                    throw new DownstreamException("usage service is unreachable", e);
                }
            }

            @Override
            public Map<String, List<Map<String, Object>>> rateForParties(
                    List<String> ownerPartyIds, String periodStart, String periodEnd) {
                try {
                    return rest.post().uri("/tmf-api/usageManagement/v4/rateUsageBatch")
                            .header("Content-Type", "application/json")
                            .body(Map.of("relatedPartyIds", ownerPartyIds,
                                    "periodStart", periodStart, "periodEnd", periodEnd))
                            .retrieve()
                            .body(new org.springframework.core.ParameterizedTypeReference<Map<String, List<Map<String, Object>>>>() {
                            });
                } catch (RestClientException e) {
                    throw new DownstreamException("usage batch rating is unreachable", e);
                }
            }
        };
    }

    @Bean
    DownstreamClients.PromotionClient promotionClient(RestClient.Builder builder,
            MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.promotion-base-url}") String baseUrl) {
        RestClient rest = client(builder, tokenInterceptor, baseUrl);
        return ownerPartyId -> {
            try {
                return rest.get()
                        .uri("/tmf-api/promotionManagement/v4/redemption?relatedPartyId={o}", ownerPartyId)
                        .retrieve()
                        .body(new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {
                        });
            } catch (RestClientException e) {
                throw new DownstreamException("promotion service is unreachable", e);
            }
        };
    }

    /** The member's loyalty tier — a pricing-context enrichment. Fail-open:
     * no loyalty component (or no member) simply means no tier benefits. */
    @Bean
    @SuppressWarnings("unchecked")
    java.util.function.Function<String, String> loyaltyTierClient(RestClient.Builder builder,
            MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.loyalty-base-url:http://localhost:8114}") String loyaltyUrl) {
        RestClient rest = client(builder, tokenInterceptor, loyaltyUrl);
        return partyId -> {
            try {
                Map<String, Object> res = rest.get()
                        .uri("/tmf-api/loyaltyManagement/v4/tier?partyId=" + partyId)
                        .retrieve().body(Map.class);
                String tier = res == null ? null : String.valueOf(res.get("tier"));
                return tier == null || "none".equals(tier) || "null".equals(tier) ? null : tier;
            } catch (RuntimeException e) {
                // fail-open includes a tenant with NO machine credentials
                // (test registries): no tier, never a failed billing run
                return null;
            }
        };
    }

    @Bean
    @SuppressWarnings("unchecked")
    DownstreamClients.PricingClient pricingClient(RestClient.Builder builder,
            MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.policy-base-url}") String baseUrl) {
        RestClient rest = client(builder, tokenInterceptor, baseUrl);
        return context -> {
            try {
                Map<String, Object> result = rest.post()
                        .uri("/tmf-api/policyManagement/v4/price")
                        .header("Content-Type", "application/json")
                        .body(Map.of("context", context))
                        .retrieve()
                        .body(Map.class);
                Object adj = result == null ? null : result.get("adjustments");
                return adj instanceof List ? (List<Map<String, Object>>) adj : List.of();
            } catch (RestClientException e) {
                // Fail open: a policy outage must not stop a billing run.
                return List.of();
            }
        };
    }

    @Bean
    @SuppressWarnings("unchecked")
    DownstreamClients.OrgClient orgClient(RestClient.Builder builder,
            MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.party-base-url}") String baseUrl) {
        RestClient rest = client(builder, tokenInterceptor, baseUrl);
        return new DownstreamClients.OrgClient() {
            @Override
            @SuppressWarnings("unchecked")
            public java.util.Optional<Map<String, Object>> partyOf(String partyId) {
                try {
                    Map<String, Object> person = rest.get()
                            .uri("/tmf-api/party/v4/individual/{id}", partyId)
                            .retrieve().body(Map.class);
                    return java.util.Optional.ofNullable(person);
                } catch (RestClientException e) {
                    // fail open: no party view means a minimal letter, never a lost bill
                    return java.util.Optional.empty();
                }
            }

            @Override
            @SuppressWarnings("unchecked")
            public java.util.Optional<Integer> billingAnchorDayOf(String partyId) {
                try {
                    Map<String, Object> person = rest.get()
                            .uri("/tmf-api/party/v4/individual/{id}", partyId)
                            .retrieve().body(Map.class);
                    if (person != null && person.get("billingAnchorDay") != null) {
                        return java.util.Optional.of(
                                Integer.valueOf(String.valueOf(person.get("billingAnchorDay"))));
                    }
                    return java.util.Optional.empty();
                } catch (RestClientException e) {
                    // fail open: an unreachable party source means calendar months
                    return java.util.Optional.empty();
                }
            }

            @Override
            @SuppressWarnings("unchecked")
            public java.util.Optional<String> billDeliveryOf(String partyId) {
                try {
                    Map<String, Object> person = rest.get()
                            .uri("/tmf-api/party/v4/individual/{id}", partyId)
                            .retrieve().body(Map.class);
                    return person == null || person.get("billDelivery") == null
                            ? java.util.Optional.empty()
                            : java.util.Optional.of(String.valueOf(person.get("billDelivery")));
                } catch (RestClientException e) {
                    // fail open: unreachable party source means the tenant default
                    return java.util.Optional.empty();
                }
            }

            @Override
            @SuppressWarnings("unchecked")
            public java.util.Optional<String> organizationOf(String partyId) {
                try {
                    Map<String, Object> person = rest.get()
                            .uri("/tmf-api/party/v4/individual/{id}", partyId)
                            .retrieve().body(Map.class);
                    if (person != null && person.get("organization") instanceof Map<?, ?> org
                            && org.get("id") != null) {
                        return java.util.Optional.of(String.valueOf(org.get("id")));
                    }
                    return java.util.Optional.empty();
                } catch (RestClientException e) {
                    // fail open: an unreachable party service means per-person bills
                    return java.util.Optional.empty();
                }
            }

            @Override
            @SuppressWarnings("unchecked")
            public java.util.Optional<Map<String, Object>> deviceAllowanceOf(String orgId) {
                try {
                    Map<String, Object> org = rest.get()
                            .uri("/tmf-api/party/v4/organization/{id}", orgId)
                            .retrieve().body(Map.class);
                    if (org != null && org.get("deviceAllowance") instanceof Map<?, ?> allowance
                            && allowance.get("value") != null) {
                        return java.util.Optional.of((Map<String, Object>) allowance);
                    }
                    return java.util.Optional.empty();
                } catch (RestClientException e) {
                    // fail open: no reachable policy means the company pays in full
                    return java.util.Optional.empty();
                }
            }
        };
    }

    @Bean
    DownstreamClients.SomClient somClient(RestClient.Builder builder,
            MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.som-base-url:http://localhost:8104}") String baseUrl) {
        RestClient rest = client(builder, tokenInterceptor, baseUrl);
        return new DownstreamClients.SomClient() {
            @Override
            public List<Map<String, Object>> servicesOf(String partyId) {
                try {
                    List<Map<String, Object>> page = rest.get()
                            .uri("/tmf-api/serviceInventory/v4/service?relatedPartyId={p}&limit=100", partyId)
                            .retrieve()
                            .body(new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {
                            });
                    return page == null ? List.of() : page;
                } catch (RestClientException e) {
                    throw new DownstreamException("service orchestration is unreachable", e);
                }
            }

            private void post(String path, Map<String, Object> body) {
                try {
                    rest.post().uri("/tmf-api/serviceInventory/v4" + path)
                            .header("Content-Type", "application/json")
                            .body(body)
                            .retrieve().toBodilessEntity();
                } catch (RestClientException e) {
                    // enforcement is fail-closed: the case does not advance
                    // past an enforcement the network never executed
                    throw new DownstreamException("service orchestration refused " + path, e);
                }
            }

            @Override
            public void restrict(String serviceId, String reason, Map<String, Object> profile) {
                post("/service/" + serviceId + "/restrict",
                        Map.of("reason", reason, "profile", profile));
            }

            @Override
            public void unrestrict(String serviceId) {
                post("/service/" + serviceId + "/unrestrict", Map.of());
            }

            @Override
            public void suspend(String serviceId, String reason) {
                post("/service/" + serviceId + "/suspend", Map.of("reason", reason));
            }

            @Override
            public void resume(String serviceId) {
                post("/service/" + serviceId + "/resume", Map.of());
            }
        };
    }

    @Bean
    DownstreamClients.PaymentClient paymentClient(RestClient.Builder builder,
            MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.payment-base-url}") String baseUrl) {
        RestClient rest = client(builder, tokenInterceptor, baseUrl);
        return new DownstreamClients.PaymentClient() {
            @Override
            @SuppressWarnings("unchecked")
            public String recordExternal(String ownerPartyId, BigDecimal amount, String unit,
                    String description, String reference, String correlatorId) {
                try {
                    Map<String, Object> created = rest.post()
                            .uri("/tmf-api/paymentManagement/v4/payment/external")
                            .header("Content-Type", "application/json")
                            .body(Map.of(
                                    "amount", Map.of("unit", unit, "value", amount),
                                    "ownerPartyId", ownerPartyId,
                                    "description", description,
                                    "reference", reference,
                                    "correlatorId", correlatorId))
                            .retrieve().body(Map.class);
                    return String.valueOf(created.get("id"));
                } catch (RestClientException e) {
                    // money is fail-closed: no phantom payments on a flaky wire
                    throw new DownstreamException("payment service refused the bank payment", e);
                }
            }

            @Override
            public String validateAuthorized(String paymentId, String expectedOwnerPartyId, BigDecimal minimum) {
                Map<String, Object> payment;
                try {
                    payment = rest.get().uri("/tmf-api/paymentManagement/v4/payment/" + paymentId)
                            .retrieve().body(Map.class);
                } catch (HttpClientErrorException.NotFound e) {
                    return "payment '" + paymentId + "' not found";
                } catch (RestClientException e) {
                    throw new DownstreamException("payment service is unreachable", e);
                }
                if (!"authorized".equals(payment.get("status"))) {
                    return "payment '" + paymentId + "' is '" + payment.get("status") + "', not authorized";
                }
                Object payer = ((List<Map<String, Object>>) payment.getOrDefault("relatedParty", List.of()))
                        .stream().map(p -> p.get("id")).findFirst().orElse(null);
                if (expectedOwnerPartyId != null && payer != null && !expectedOwnerPartyId.equals(payer)) {
                    return "payment '" + paymentId + "' belongs to another party";
                }
                BigDecimal amount = new BigDecimal(String.valueOf(
                        ((Map<String, Object>) payment.get("amount")).get("value")));
                if (amount.compareTo(minimum) < 0) {
                    return "payment '" + paymentId + "' covers " + amount + ", bill needs " + minimum;
                }
                return "";
            }

            @Override
            public void capture(String paymentId) {
                try {
                    rest.patch().uri("/tmf-api/paymentManagement/v4/payment/" + paymentId)
                            .header("Content-Type", "application/json")
                            .body(Map.of("status", "captured"))
                            .retrieve().toBodilessEntity();
                } catch (RestClientException e) {
                    throw new DownstreamException("payment service rejected the capture", e);
                }
            }

            @Override
            public void refund(String paymentId, java.math.BigDecimal amount, String reason) {
                rest.post().uri("/tmf-api/paymentManagement/v4/payment/" + paymentId + "/refund")
                        .header("Content-Type", "application/json")
                        .body(Map.of("amount", Map.of("value", amount),
                                "reason", reason == null ? "dispute credit" : reason))
                        .retrieve().toBodilessEntity();
            }
        };
    }

    /** The e-invoice rail's alias lookup — an EXTERNAL rail (its own
     * credential model), so no machine-token interceptor. */
    @Bean
    DownstreamClients.AliasLookupClient aliasLookupClient(RestClient.Builder builder,
            @Value("${bss.downstream.einvoice-rail-base-url:http://localhost:8147}") String baseUrl) {
        RestClient rest = builder.baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory()).build();
        return party -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> answer = rest.post().uri("/alias/lookup")
                        .header("Content-Type", "application/json")
                        .body(Map.of(
                                "name", nameOf(party),
                                "email", contactOf(party, "email", "emailAddress"),
                                "phone", contactOf(party, "mobile", "phoneNumber")))
                        .retrieve().body(Map.class);
                return answer == null || answer.get("aliasRef") == null
                        ? java.util.Optional.empty()
                        : java.util.Optional.of(String.valueOf(answer.get("aliasRef")));
            } catch (RestClientException e) {
                // no answer is a MISS: the chain falls to the next channel
                return java.util.Optional.empty();
            }
        };
    }

    @Bean
    DownstreamClients.DirectDebitClient directDebitClient(RestClient.Builder builder,
            @Value("${bss.downstream.directdebit-rail-base-url:http://localhost:8149}") String baseUrl) {
        RestClient rest = builder.baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory()).build();
        return claim -> rest.post().uri("/claims")
                .header("Content-Type", "application/json")
                .body(claim).retrieve().toBodilessEntity();
    }

    private static String nameOf(Map<String, Object> party) {
        if (party == null) {
            return "";
        }
        String given = party.get("givenName") == null ? "" : String.valueOf(party.get("givenName"));
        String family = party.get("familyName") == null ? "" : String.valueOf(party.get("familyName"));
        return (given + " " + family).trim();
    }

    @SuppressWarnings("unchecked")
    private static String contactOf(Map<String, Object> party, String mediumType, String key) {
        if (party == null || !(party.get("contactMedium") instanceof List<?> media)) {
            return "";
        }
        for (Object m : media) {
            if (m instanceof Map<?, ?> medium && mediumType.equals(medium.get("mediumType"))
                    && medium.get("characteristic") instanceof Map<?, ?> c && c.get(key) != null) {
                return String.valueOf(c.get(key));
            }
        }
        return "";
    }
}
