package com.bss.som.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * What the inventory's line actions answer: the SIM behind a service, the
 * receipts of a PIN reset, a SIM swap, a number change, a barring, a
 * transfer, a diagnosis, the router state, a restart. Where the receipt IS
 * the event with a label on top, the label is {@code NON_NULL} so the
 * published event stays the shape it always was.
 */
public final class LineReceipts {

    private LineReceipts() {
    }

    /** The SIM behind a numbered service: masked ICCID; the PUK only when revealed. */
    @JsonPropertyOrder({"serviceId", "iccid", "puk", "@type"})
    public record SimView(String serviceId, String iccid,
            @JsonInclude(JsonInclude.Include.NON_NULL) String puk,
            @JsonProperty("@type") String type) {
    }

    @JsonPropertyOrder({"status", "@type"})
    public record SimPinReset(String status, @JsonProperty("@type") String type) {

        public static SimPinReset done() {
            return new SimPinReset("done", "SimPinReset");
        }
    }

    @JsonPropertyOrder({"serviceId", "reason", "oldSim", "iccid", "note", "@type"})
    public record SimReplacement(String serviceId, String reason, OldSim oldSim, String iccid, String note,
            @JsonProperty("@type") String type) {

        @JsonPropertyOrder({"iccid", "status"})
        public record OldSim(String iccid, String status) {
        }
    }

    @JsonPropertyOrder({"serviceId", "oldNumber", "number", "note", "@type"})
    public record NumberChange(String serviceId, String oldNumber, String number, String note,
            @JsonProperty("@type") String type) {
    }

    /** The barring event (restrict / unrestrict) and, labelled, the answer. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"id", "name", "state", "reason", "restrictionProfile", "relatedParty", "@type"})
    public record ServiceRestriction(String id, String name, String state, String reason,
            Map<String, Object> restrictionProfile, List<PartyRef> relatedParty,
            @JsonProperty("@type") String type) {

        public ServiceRestriction labelled() {
            return new ServiceRestriction(id, name, state, reason, restrictionProfile, relatedParty, "ServiceRestriction");
        }
    }

    /** The transfer event (giver and receiver) and, labelled, the answer. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"serviceId", "name", "number", "relatedParty", "@type"})
    public record ServiceTransfer(String serviceId, String name, String number, List<PartyRef> relatedParty,
            @JsonProperty("@type") String type) {

        public ServiceTransfer labelled() {
            return new ServiceTransfer(serviceId, name, number, relatedParty, "ServiceTransfer");
        }
    }

    /** The triage before a ticket: a verdict and every finding, in the order the checks ran. */
    @JsonPropertyOrder({"serviceId", "name", "verdict", "findings", "@type"})
    public record ServiceDiagnosis(String serviceId, String name, String verdict, List<Finding> findings,
            @JsonProperty("@type") String type) {
    }

    @JsonPropertyOrder({"code", "severity", "message"})
    public record Finding(String code, String severity, String message) {

        public static Finding cause(String code, String message) {
            return new Finding(code, "cause", message);
        }

        public static Finding caution(String code, String message) {
            return new Finding(code, "caution", message);
        }

        public static Finding info(String code, String message) {
            return new Finding(code, "info", message);
        }
    }

    /** The router or ONT on a line, as the ACS sees it. */
    @JsonPropertyOrder({"serviceId", "state", "uptimeSeconds", "firmware", "firmwareOutdated", "wifiClients",
            "model", "serial", "lastSeen", "@type"})
    public record CpeView(String serviceId, String state, long uptimeSeconds, String firmware,
            boolean firmwareOutdated, int wifiClients, String model, String serial, String lastSeen,
            @JsonProperty("@type") String type) {
    }

    @JsonPropertyOrder({"serviceId", "state", "said", "@type"})
    public record CpeRestart(String serviceId, String state, String said, @JsonProperty("@type") String type) {

        public static CpeRestart sent(String serviceId) {
            return new CpeRestart(serviceId, "rebooting",
                    "Restart sent to the router — it is back in about a minute.", "CpeRestart");
        }
    }

    /** The small error the equipment door answers with (503 / 409). */
    @JsonPropertyOrder({"code", "reason", "@type"})
    public record ErrorView(String code, String reason, @JsonProperty("@type") String type) {

        public static ErrorView of(int code, String reason) {
            return new ErrorView(String.valueOf(code), reason, "Error");
        }
    }

    @JsonPropertyOrder({"number", "partyId"})
    public record NumberOwner(String number, String partyId) {
    }

    /** One available number in the choose-your-number shortlist. */
    public record NumberOffer(String msisdn) {
    }

    /**
     * The lifecycle event a pause, a resume or a cease publishes — and what
     * the door answers: the same record. Each path writes only its own
     * facts; {@code NON_NULL} leaves the others off.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"id", "name", "state", "reason", "resumeAt", "resumedBy", "releasedNumber", "relatedParty"})
    public record ServiceStateReceipt(String id, String name, String state, String reason, String resumeAt,
            String resumedBy, String releasedNumber, List<PartyRef> relatedParty) {

        public static ServiceStateReceipt suspended(String id, String name, String state, String reason,
                String resumeAt, String ownerPartyId) {
            return new ServiceStateReceipt(id, name, state, reason, resumeAt, null, null,
                    PartyRef.customerListOrNull(ownerPartyId));
        }

        public static ServiceStateReceipt resumed(String id, String name, String state, String how,
                String ownerPartyId) {
            return new ServiceStateReceipt(id, name, state, null, null, how, null,
                    PartyRef.customerListOrNull(ownerPartyId));
        }

        public static ServiceStateReceipt terminated(String id, String name, String state, String reason,
                String releasedNumber, String ownerPartyId) {
            return new ServiceStateReceipt(id, name, state, reason, null, null, releasedNumber,
                    PartyRef.customerListOrNull(ownerPartyId));
        }
    }
}
