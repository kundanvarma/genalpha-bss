package com.bss.billing.client;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Attaches a client-credentials bearer token to outgoing service-to-service
 * calls. The billing service authenticates as its own machine identity
 * (registration "bss-m2m"), whose scopes are limited to exactly what
 * orchestration needs: inventory:read, payment:read, payment:write. The
 * authorized-client manager caches the token and refreshes it on expiry.
 */
@Component
public class MachineTokenInterceptor implements ClientHttpRequestInterceptor {

    private static final String REGISTRATION_ID = "bss-m2m";

    private final OAuth2AuthorizedClientManager authorizedClientManager;

    public MachineTokenInterceptor(OAuth2AuthorizedClientManager authorizedClientManager) {
        this.authorizedClientManager = authorizedClientManager;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId(REGISTRATION_ID)
                .principal("billing")
                .build();
        OAuth2AuthorizedClient client = authorizedClientManager.authorize(authorizeRequest);
        if (client == null) {
            throw new IllegalStateException("client credentials authorization failed for " + REGISTRATION_ID);
        }
        request.getHeaders().setBearerAuth(client.getAccessToken().getTokenValue());
        return execution.execute(request, body);
    }
}
