package com.bss.intelligence.door;

import com.bss.intelligence.service.Redaction;
import com.bss.intelligence.service.Redactor;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * One model API the door speaks (#283) — the seam, one adapter per vendor API.
 * The door itself (keys, tenant, guardrails, redaction, metering, the ledger) is
 * the same for every provider; what differs is the path, how the provider's key
 * is presented, where the token counts sit in an answer, what text a request
 * carries, and the error shape a worker's SDK expects back.
 */
interface DoorProtocol {

    /** The kinds of refusal the door makes, rendered in each provider's own words. */
    enum Refusal { AUTH, BILLING, INVALID, NOT_FOUND, UPSTREAM, UNAVAILABLE }

    /** The provider column in the ledger. */
    String name();

    /** Does this adapter serve a tenant whose worker-ai-provider is this? */
    boolean serves(String tenantProvider);

    /** The provider's own base URL, when the tenant does not name one. */
    String defaultBase();

    /** The path the call is forwarded to, under the base. */
    String path();

    /** Present the TENANT's key to the provider, plus any headers worth passing through. */
    void authenticate(HttpHeaders headers, String providerKey, Map<String, String> passthrough);

    /** The provider's own token counts in an answer. */
    DoorPricing.Usage usage(JsonNode answer);

    /** The shape of an answer for the ledger — never its text. */
    String summary(JsonNode answer);

    /** Redact a request in place: every piece of text it carries, never an id or a signed block. */
    void redact(JsonNode request, Redactor redactor, Redaction map);

    /** Reverse the placeholders in an answer in place, including inside the tool calls it asks for. */
    void restore(JsonNode answer, Redaction map);

    /** A refusal in the provider's own error shape, so the worker's SDK reads it like the provider's. */
    String error(ObjectMapper json, Refusal kind, String message);
}
