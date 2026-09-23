package com.bss.entitlement.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The envelope every TS.43 and RCC.14 answer carries, and the two small
 * bodies the door sends on its own: the version block, the token block, an
 * EAP relay packet and a refusal. Key order is the wire's, not the
 * declaration's — {@code Map.of} had been re-salting both two-key blocks on
 * every JVM start, and these are the orders the answer has today.
 */
public final class Ts43Envelope {

    private Ts43Envelope() {
    }

    /** VERS: the entitlement configuration version and how long it stands. */
    @JsonPropertyOrder({"validity", "version"})
    public record Vers(@JsonProperty("validity") String validity, @JsonProperty("version") String version) {
    }

    /** TOKEN: the credential a phone may present instead of running EAP-AKA again. */
    @JsonPropertyOrder({"token", "validity"})
    public record Token(@JsonProperty("token") String token, @JsonProperty("validity") String validity) {
    }

    /** The EAP relay body ({@code application/vnd.gsma.eap-relay.v1.0+json}). */
    @JsonPropertyOrder({"eap-relay-packet"})
    public record EapRelay(@JsonProperty("eap-relay-packet") String packet) {
    }

    /** A refusal from the TS.43 / RCC.14 door — never a TM Forum error body. */
    @JsonPropertyOrder({"error"})
    public record Refusal(@JsonProperty("error") String error) {
    }
}
