package com.bss.payment.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TMF670 payment method as it rides on a TMF676 payment — the open edge.
 *
 * <p>On the way IN it may be a bare reference ({@code id}) to a vaulted method,
 * or the card details the PSP needs for this one authorization; those extra keys
 * (cardNumber, expiry, cvc, token, brand …) stay open in {@code extensions} and
 * are never stored. On the way OUT only the masked pair {@code @type} +
 * {@code label} is written.
 *
 * <p>Built through a DELEGATING creator so the posted key order survives: a
 * creator-bound {@code @JsonAnySetter} hands the unknown keys back last-first.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"@type", "id", "label"})
public record PaymentMethodRef(
        @JsonProperty("@type") String type,
        @JsonProperty("id") String id,
        @JsonProperty("label") String label,
        @JsonAnyGetter Map<String, Object> extensions) {

    public PaymentMethodRef {
        extensions = extensions == null ? Map.of() : new LinkedHashMap<>(extensions);
    }

    /** The masked pair a stored payment echoes. */
    public static PaymentMethodRef masked(String type, String label) {
        return new PaymentMethodRef(type, null, label, null);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static PaymentMethodRef fromMap(Map<String, Object> posted) {
        if (posted == null) {
            return null;
        }
        Map<String, Object> rest = new LinkedHashMap<>(posted);
        Object type = rest.remove("@type");
        Object id = rest.remove("id");
        Object label = rest.remove("label");
        return new PaymentMethodRef(
                type == null ? null : String.valueOf(type),
                id == null ? null : String.valueOf(id),
                label == null ? null : String.valueOf(label),
                rest);
    }
}
