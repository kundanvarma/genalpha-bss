package com.bss.usage.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.Optional;
import static com.bss.usage.api.Wire.textOf;

@Component
public class RestNumberClient implements NumberClient {

    private final RestClient restClient;

    public RestNumberClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.som-base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    /** Fails CLOSED: an unresolvable number is "nobody", never a guess. */
    @Override
    @SuppressWarnings("unchecked")
    public Optional<String> ownerOfNumber(String number) {
        try {
            Map<String, Object> owner = restClient.get()
                    .uri(uri -> uri.path("/tmf-api/serviceInventory/v4/numberOwner")
                            .queryParam("number", number).build())
                    .retrieve().body(Map.class);
            return Optional.ofNullable(textOf(owner, "partyId"));
        } catch (RestClientException e) {
            return Optional.empty();
        }
    }
}
