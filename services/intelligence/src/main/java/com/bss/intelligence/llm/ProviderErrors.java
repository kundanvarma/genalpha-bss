package com.bss.intelligence.llm;

import org.springframework.web.client.RestClientResponseException;

/**
 * A model call's failure, for a log line: the error class and, when the
 * provider answered, its HTTP status — never the exception's message. A
 * provider's error text can quote the prompt back (Spring puts the response
 * body in a {@link RestClientResponseException}'s message), and for a tenant
 * on raw exposure that prompt is unredacted. The span code has the same rule
 * ({@code GenAiTracing.failed}).
 */
public final class ProviderErrors {

    private ProviderErrors() {
    }

    /** "org.springframework.web.client.HttpServerErrorException$ServiceUnavailable (HTTP 503)". */
    public static String describe(Throwable e) {
        if (e == null) {
            return "unknown";
        }
        if (e instanceof RestClientResponseException r) {
            return e.getClass().getName() + " (HTTP " + r.getStatusCode().value() + ")";
        }
        return e.getClass().getName();
    }
}
