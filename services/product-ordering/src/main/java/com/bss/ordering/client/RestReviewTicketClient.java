package com.bss.ordering.client;

import com.bss.ordering.exception.DownstreamException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Files the review ticket UNDER THE CALLER'S OWN TOKEN, not ordering's machine
 * identity: the customer is the one asking, so the ticket is theirs — the
 * trouble-ticket component scopes it to them from their token, they can follow
 * it on their Support page, and the tenant follows the token. (Ordering's
 * machine identity holds no ticket authority, and should not: it would let
 * ordering file tickets as anyone.) A staff caller asking on a customer's
 * behalf files it as staff, naming the customer in relatedParty.
 */
@Component
public class RestReviewTicketClient implements ReviewTicketClient {

    private static final String PATH = "/tmf-api/troubleTicket/v4/troubleTicket";

    private static final String DECISION = "CreditDecision";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Created(String id) {
    }

    /** A ticket as the list answers it — only what the lookup reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Filed(String id, JsonNode relatedEntity) {

        /** Names this decision? relatedEntity is whatever the filer wrote — read it, never assume its shape. */
        boolean names(String decisionId) {
            if (id == null || relatedEntity == null || !relatedEntity.isArray()) {
                return false;
            }
            for (JsonNode ref : relatedEntity) {
                if (DECISION.equals(text(ref.path("@referredType"))) && decisionId.equals(text(ref.path("id")))) {
                    return true;
                }
            }
            return false;
        }

        private static String text(JsonNode node) {
            return node.isString() ? node.asString() : null;
        }
    }

    private final RestClient restClient;

    public RestReviewTicketClient(RestClient.Builder builder,
            @Value("${bss.downstream.ticket-base-url:http://localhost:8092}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /**
     * Idempotent by the decision the ticket names (#422 review, finding 14).
     * A care queue that took the ticket but answered after our read timeout
     * leaves a ticket we were never told about; the customer retries, and
     * filing again would put two reviews in the queue. So before creating,
     * look for a creditReview ticket of this customer whose relatedEntity
     * names this CreditDecision — trouble-ticket has no externalId filter, but
     * the reference rides the standard relatedEntity, and the list scopes to
     * the customer anyway. A lookup that fails is a failure: guessing "none"
     * is exactly how the duplicate is filed.
     */
    @Override
    public String open(ReviewTicket ticket) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt)) {
            throw new DownstreamException("a review ticket needs the caller's own token", null);
        }
        String reference = ticket.relatedEntity() == null ? null : ticket.relatedEntity().stream()
                .filter(e -> DECISION.equals(e.referredType())).map(EntityRef::id).findFirst().orElse(null);
        String customer = ticket.relatedParty() == null || ticket.relatedParty().isEmpty() ? null
                : ticket.relatedParty().get(0).id();
        try {
            if (reference != null) {
                List<Filed> filed = restClient.get()
                        .uri(u -> u.path(PATH).queryParam("ticketType", ticket.ticketType())
                                .queryParamIfPresent("relatedPartyId", java.util.Optional.ofNullable(customer))
                                .queryParam("limit", 100).build())
                        .headers(h -> h.setBearerAuth(jwt.getToken().getTokenValue()))
                        .retrieve()
                        .body(new ParameterizedTypeReference<List<Filed>>() { });
                if (filed != null) {
                    for (Filed f : filed) {
                        if (f.names(reference)) {
                            return f.id();
                        }
                    }
                }
            }
            Created created = restClient.post().uri(PATH)
                    .headers(h -> h.setBearerAuth(jwt.getToken().getTokenValue()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ticket)
                    .retrieve()
                    .body(Created.class);
            if (created == null || created.id() == null || created.id().isBlank()) {
                throw new DownstreamException("the ticket service answered without a ticket id", null);
            }
            return created.id();
        } catch (RestClientException e) {
            throw new DownstreamException("the review could not be filed with customer care — try again shortly", e);
        }
    }
}
