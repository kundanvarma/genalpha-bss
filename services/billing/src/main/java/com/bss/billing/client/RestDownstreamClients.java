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
        return () -> {
            List<Map<String, Object>> all = new ArrayList<>();
            int offset = 0;
            while (true) {
                try {
                    List<Map<String, Object>> page = rest.get()
                            .uri("/tmf-api/productInventory/v4/product?status=active&offset={o}&limit=100", offset)
                            .retrieve()
                            .body(new org.springframework.core.ParameterizedTypeReference<>() {
                            });
                    all.addAll(page);
                    if (page.size() < 100) {
                        return all;
                    }
                    offset += 100;
                } catch (RestClientException e) {
                    throw new DownstreamException("product-inventory is unreachable", e);
                }
            }
        };
    }

    @Bean
    DownstreamClients.CatalogClient catalogClient(RestClient.Builder builder,
            MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.catalog-base-url}") String baseUrl) {
        RestClient rest = client(builder, tokenInterceptor, baseUrl);
        return new DownstreamClients.CatalogClient() {
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
        return (ownerPartyId, periodStart, periodEnd) -> {
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
        };
    }
}
