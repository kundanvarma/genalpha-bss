package com.bss.ordering.exception;

import com.bss.ordering.dto.CreditDecisionLink;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * TMF-style error representation.
 */
public class ErrorResponse {

    @JsonProperty("code")
    private String code;

    @JsonProperty("reason")
    private String reason;

    @JsonProperty("message")
    private String message;

    @JsonProperty("status")
    private String status;

    @JsonProperty("@type")
    private String type = "Error";

    /**
     * House field beside the TMF Error (#422 review): a refusal that left a
     * credit decision behind points at it, so the customer can still read why
     * and ask a person. Absent on every other error.
     */
    @JsonProperty("creditDecision")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private CreditDecisionLink creditDecision;

    public ErrorResponse() {
    }

    public CreditDecisionLink getCreditDecision() {
        return creditDecision;
    }

    public void setCreditDecision(CreditDecisionLink creditDecision) {
        this.creditDecision = creditDecision;
    }

    public ErrorResponse(String code, String reason, String message, String status) {
        this.code = code;
        this.reason = reason;
        this.message = message;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
