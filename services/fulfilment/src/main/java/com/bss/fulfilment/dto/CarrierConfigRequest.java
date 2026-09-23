package com.bss.fulfilment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A carrier binding as the operator saves it. The two flags keep the map's
 * leniency exactly: {@code Boolean.TRUE.equals(...)} made only a JSON
 * {@code true} the default, and {@code !Boolean.FALSE.equals(...)} disabled a
 * carrier only on a JSON {@code false} — the string "false" and the number 0
 * left it enabled, and a {@code Boolean} field would silently have changed that.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CarrierConfigRequest(JsonNode carrier, JsonNode displayName, JsonNode baseUrl,
                                   JsonNode secretRef, JsonNode methods, JsonNode config,
                                   JsonNode postcodePrefix, JsonNode isDefault, JsonNode enabled) {

    public static final CarrierConfigRequest EMPTY =
            new CarrierConfigRequest(null, null, null, null, null, null, null, null, null);

    public String carrierKey() {
        return Json.textOrNull(carrier);
    }

    /** {@code getOrDefault("displayName", carrier)}: a key present with a JSON null wins the null. */
    public String displayNameOr(String fallback) {
        return Json.present(displayName) ? Json.textOrNull(displayName) : fallback;
    }

    public boolean makeDefault() {
        return isDefault != null && isDefault.isBoolean() && isDefault.booleanValue();
    }

    public boolean stayEnabled() {
        return !(enabled != null && enabled.isBoolean() && !enabled.booleanValue());
    }
}
