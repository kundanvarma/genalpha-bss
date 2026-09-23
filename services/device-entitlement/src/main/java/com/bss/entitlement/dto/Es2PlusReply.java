package com.bss.entitlement.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The SM-DP+ notification handshake (SGP.22 ES2+ §5.3.5
 * {@code handleDownloadProgressInfo}): the standard's execution-status
 * header, and beside it what the ECS actually applied.
 */
@JsonPropertyOrder({"header", "applied"})
public record Es2PlusReply(Header header, ProfileProgress applied) {

    public static Es2PlusReply executedSuccess(ProfileProgress applied) {
        return new Es2PlusReply(new Header(new FunctionExecutionStatus("Executed-Success")), applied);
    }

    @JsonPropertyOrder({"functionExecutionStatus"})
    public record Header(FunctionExecutionStatus functionExecutionStatus) {
    }

    @JsonPropertyOrder({"status"})
    public record FunctionExecutionStatus(String status) {
    }

    /**
     * What the notification moved: the profile's state, and the companion or
     * transfer it belonged to when one matched. The two references are
     * {@code NON_NULL} — nothing matching leaves them off, as the map did.
     */
    @JsonPropertyOrder({"iccid", "profileState", "companion", "transfer"})
    public record ProfileProgress(String iccid, String profileState,
            @JsonInclude(JsonInclude.Include.NON_NULL) String companion,
            @JsonInclude(JsonInclude.Include.NON_NULL) String transfer) {

        public ProfileProgress(String iccid, String profileState) {
            this(iccid, profileState, null, null);
        }

        public ProfileProgress withCompanion(String companionId) {
            return new ProfileProgress(iccid, profileState, companionId, transfer);
        }

        public ProfileProgress withTransfer(String transferId) {
            return new ProfileProgress(iccid, profileState, companion, transferId);
        }
    }
}
