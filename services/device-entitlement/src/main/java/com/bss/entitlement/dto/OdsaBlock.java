package com.bss.entitlement.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * The On-Device Service Activation answer (TS.43 §6, ap2006 companion /
 * ap2009 primary). One record carries every operation's answer: each
 * operation writes only its own facts and {@code NON_NULL} leaves the rest
 * off, so the wire is exactly what the map path wrote. The declared order is
 * the union of the operations' orders — no two operations disagree on the
 * relative order of the keys they share.
 *
 * <p>{@code DownloadInfo} keeps the {@link Map} the SGP.22 profile was
 * handed over in: nothing here reshapes an activation code.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"CompanionAppEligibility", "CompanionDeviceServices", "NotEnabledURL", "NotEnabledUserData",
    "PrimaryAppEligibility", "SubscriptionResult", "MSG", "DownloadInfo", "SubscriptionServiceURL",
    "SubscriptionServiceUserData", "ServiceStatus", "CompanionConfigurations", "PrimaryConfiguration",
    "OperationResult"})
public record OdsaBlock(
        @JsonProperty("CompanionAppEligibility") String companionAppEligibility,
        @JsonProperty("CompanionDeviceServices") String companionDeviceServices,
        @JsonProperty("NotEnabledURL") String notEnabledUrl,
        @JsonProperty("NotEnabledUserData") String notEnabledUserData,
        @JsonProperty("PrimaryAppEligibility") String primaryAppEligibility,
        @JsonProperty("SubscriptionResult") String subscriptionResult,
        @JsonProperty("MSG") Msg msg,
        @JsonProperty("DownloadInfo") Map<String, Object> downloadInfo,
        @JsonProperty("SubscriptionServiceURL") String subscriptionServiceUrl,
        @JsonProperty("SubscriptionServiceUserData") String subscriptionServiceUserData,
        @JsonProperty("ServiceStatus") String serviceStatus,
        @JsonProperty("CompanionConfigurations") List<CompanionConfigEntry> companionConfigurations,
        @JsonProperty("PrimaryConfiguration") PrimaryConfiguration primaryConfiguration,
        @JsonProperty("OperationResult") String operationResult)
        implements Ts43Block {

    public static final String SUCCESS = "1";
    public static final String ERROR_INVALID_OPERATION = "101";
    public static final String ERROR_INVALID_PARAMETER = "102";
    public static final String ERROR_INVALID_ICCID = "104";

    /** The answer that is only an operation result (an unknown operation, a bad parameter). */
    public static OdsaBlock result(String operationResult) {
        return new Draft().operationResult(operationResult).freeze();
    }

    /** The end-user message the client shows when an operation is dismissed. Key order as the wire has it. */
    @JsonPropertyOrder({"message", "title"})
    public record Msg(@JsonProperty("message") String message, @JsonProperty("title") String title) {
    }

    /** The spec wraps each companion's configuration in its own single-key object. */
    @JsonPropertyOrder({"CompanionConfiguration"})
    public record CompanionConfigEntry(
            @JsonProperty("CompanionConfiguration") CompanionConfiguration companionConfiguration) {
    }

    @JsonPropertyOrder({"ICCID", "CompanionDeviceService", "ServiceStatus", "CompanionTerminalId"})
    public record CompanionConfiguration(
            @JsonProperty("ICCID") String iccid,
            @JsonProperty("CompanionDeviceService") String companionDeviceService,
            @JsonProperty("ServiceStatus") String serviceStatus,
            @JsonProperty("CompanionTerminalId") String companionTerminalId) {
    }

    @JsonPropertyOrder({"ICCID", "ServiceStatus", "PolicyEnabled"})
    public record PrimaryConfiguration(
            @JsonProperty("ICCID") String iccid,
            @JsonProperty("ServiceStatus") String serviceStatus,
            @JsonProperty("PolicyEnabled") String policyEnabled) {
    }

    /**
     * The answer is written key by key along one operation's path, so the
     * service fills a draft and freezes it once — the same shape the
     * {@code LinkedHashMap} had, with a compiler between the keys and the
     * spelling.
     */
    public static final class Draft {
        private String companionAppEligibility;
        private String companionDeviceServices;
        private String notEnabledUrl;
        private String notEnabledUserData;
        private String primaryAppEligibility;
        private String subscriptionResult;
        private Msg msg;
        private Map<String, Object> downloadInfo;
        private String subscriptionServiceUrl;
        private String subscriptionServiceUserData;
        private String serviceStatus;
        private List<CompanionConfigEntry> companionConfigurations;
        private PrimaryConfiguration primaryConfiguration;
        private String operationResult;

        public Draft companionEligibility(String value, String services) {
            this.companionAppEligibility = value;
            this.companionDeviceServices = services;
            return this;
        }

        public Draft notEnabled(String url, String userData) {
            this.notEnabledUrl = url;
            this.notEnabledUserData = userData;
            return this;
        }

        public Draft primaryEligibility(String value) {
            this.primaryAppEligibility = value;
            return this;
        }

        public Draft subscriptionResult(String value) {
            this.subscriptionResult = value;
            return this;
        }

        public Draft msg(String title, String message) {
            this.msg = new Msg(message, title);
            return this;
        }

        public Draft downloadInfo(Map<String, Object> value) {
            this.downloadInfo = value;
            return this;
        }

        public Draft subscriptionService(String url, String userData) {
            this.subscriptionServiceUrl = url;
            this.subscriptionServiceUserData = userData;
            return this;
        }

        public Draft serviceStatus(String value) {
            this.serviceStatus = value;
            return this;
        }

        public Draft companionConfigurations(List<CompanionConfigEntry> value) {
            this.companionConfigurations = value;
            return this;
        }

        public Draft primaryConfiguration(PrimaryConfiguration value) {
            this.primaryConfiguration = value;
            return this;
        }

        public Draft operationResult(String value) {
            this.operationResult = value;
            return this;
        }

        public OdsaBlock freeze() {
            return new OdsaBlock(companionAppEligibility, companionDeviceServices, notEnabledUrl,
                    notEnabledUserData, primaryAppEligibility, subscriptionResult, msg, downloadInfo,
                    subscriptionServiceUrl, subscriptionServiceUserData, serviceStatus,
                    companionConfigurations, primaryConfiguration, operationResult);
        }
    }
}
