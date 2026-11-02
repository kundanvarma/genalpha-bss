package com.bss.ordering.client;

import com.bss.ordering.exception.DownstreamException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Created(String id) {
    }

    private final RestClient restClient;

    public RestReviewTicketClient(RestClient.Builder builder,
            @Value("${bss.downstream.ticket-base-url:http://localhost:8092}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public String open(ReviewTicket ticket) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt)) {
            throw new DownstreamException("a review ticket needs the caller's own token", null);
        }
        try {
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
