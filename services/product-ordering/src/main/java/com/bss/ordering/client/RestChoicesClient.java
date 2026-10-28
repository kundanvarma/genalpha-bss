package com.bss.ordering.client;

import com.bss.ordering.exception.DownstreamException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/** The ledger over HTTP, as ordering's machine identity (the only caller allowed to move Choices). */
@Component
@ConditionalOnProperty(name = "bss.choices.enabled", havingValue = "true", matchIfMissing = true)
public class RestChoicesClient implements ChoicesClient {

    private static final String BASE = "/benefitAllowance/v1";

    private final RestClient restClient;

    public RestChoicesClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.choices-base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    @Override
    public HoldOutcome hold(String ownerPartyId, String orderId, String orderItemId, String offeringId,
            String offeringName, int cost, List<Source> replaces, Terms terms) {
        try {
            restClient.post()
                    .uri(BASE + "/hold")
                    .body(new HoldBody(ownerPartyId, orderId, orderItemId, offeringId, offeringName, cost,
                            replaces == null ? List.of() : replaces, terms.holdMonths(), terms.partner(),
                            terms.wholesaleCost()))
                    .retrieve()
                    .toBodilessEntity();
            return HoldOutcome.held();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT || e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                // 409: not enough Choices left; 400: this tenant does not offer them
                return HoldOutcome.refused(messageIn(e, offeringName));
            }
            throw new DownstreamException("benefit-allowance rejected the hold", e);
        } catch (RestClientException e) {
            throw new DownstreamException("benefit-allowance is unreachable", e);
        }
    }

    @Override
    public void settle(String orderId, String ownerPartyId, List<Grant> grants, List<Ending> endings,
            List<Regrant> regrants) {
        post("/order/" + orderId + "/settle", new SettleBody(ownerPartyId, grants, endings, regrants));
    }

    @Override
    public Pool pool(String ownerPartyId) {
        try {
            Pool pool = restClient.get().uri(BASE + "/pool?ownerPartyId={owner}", ownerPartyId)
                    .retrieve().body(Pool.class);
            return pool == null ? new Pool(0, 0, List.of(), List.of(), 0, null) : pool;
        } catch (RestClientException e) {
            throw new DownstreamException("benefit-allowance is unreachable", e);
        }
    }

    @Override
    public Standing standing(String ownerPartyId) {
        try {
            PoolWire wire = restClient.get().uri(BASE + "/pool?ownerPartyId={owner}", ownerPartyId)
                    .retrieve().body(PoolWire.class);
            if (wire == null) {
                return new Standing(false, "Choices", 0, java.util.Set.of());
            }
            java.util.Set<String> picked = wire.allocations() == null ? java.util.Set.of()
                    : wire.allocations().stream().map(PickWire::offeringId).filter(java.util.Objects::nonNull)
                            .collect(java.util.stream.Collectors.toSet());
            return new Standing(wire.offered(), wire.label() == null ? "Choices" : wire.label(), wire.left(), picked);
        } catch (RestClientException e) {
            throw new DownstreamException("benefit-allowance is unreachable", e);
        }
    }

    /** The ledger's pool, as much of it as "can I pick this?" reads. */
    private record PoolWire(boolean offered, String label, int left, List<PickWire> allocations) {
    }

    private record PickWire(String offeringId) {
    }

    @Override
    public void release(String orderId) {
        post("/order/" + orderId + "/release", null);
    }

    private void post(String path, Object body) {
        try {
            var request = restClient.post().uri(BASE + path);
            (body == null ? request : request.body(body)).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new DownstreamException("benefit-allowance rejected or is unreachable", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static String messageIn(HttpClientErrorException e, String offeringName) {
        try {
            Map<String, Object> body = e.getResponseBodyAs(Map.class);
            if (body != null && body.get("message") != null) {
                return String.valueOf(body.get("message"));
            }
        } catch (RuntimeException ignored) {
            // an unreadable body: say what we know
        }
        return "not enough Choices left for " + offeringName;
    }

    private record HoldBody(String ownerPartyId, String orderId, String orderItemId, String offeringId,
            String offeringName, int cost, List<Source> replaces, Integer holdMonths, String partner,
            java.math.BigDecimal wholesaleCost) {
    }

    private record SettleBody(String ownerPartyId, List<Grant> grants, List<Ending> endings, List<Regrant> regrants) {
    }

}
