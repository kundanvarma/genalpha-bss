package com.bss.som.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The migration seam's answer, one honest shape per outcome: the row was
 * imported, it already was, or the form was refused.
 */
public sealed interface ImportReport {

    @JsonPropertyOrder({"imported", "serviceId", "name", "msisdn"})
    record Imported(boolean imported, String serviceId, String name, String msisdn) implements ImportReport {

        public static Imported of(String serviceId, String name, String msisdn) {
            return new Imported(true, serviceId, name, msisdn == null ? "" : msisdn);
        }
    }

    @JsonPropertyOrder({"imported", "reason"})
    record Skipped(boolean imported, String reason) implements ImportReport {

        public static Skipped already() {
            return new Skipped(false, "already imported");
        }
    }

    record Refused(String error) implements ImportReport {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ImportRequest(String ownerPartyId, String name, String msisdn) {
    }
}
