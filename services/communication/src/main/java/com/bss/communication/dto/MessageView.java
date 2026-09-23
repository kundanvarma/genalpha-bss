package com.bss.communication.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * TMF681 CommunicationMessage as every inbox reads it. {@code subject} and
 * {@code content} are written even when null — the map this replaces always
 * carried them — so NON_NULL sits only on the components the map left off.
 */
@JsonPropertyOrder({"id", "href", "subject", "content", "messageType", "status", "source",
        "deliveryStatus", "relatedParty", "characteristic", "sendTime", "lastUpdate", "@type"})
public record MessageView(
        @JsonProperty("id") String id,
        @JsonProperty("href") String href,
        @JsonProperty("subject") String subject,
        @JsonProperty("content") String content,
        @JsonProperty("messageType") String messageType,
        @JsonProperty("status") String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("source") String source,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("deliveryStatus") String deliveryStatus,
        @JsonProperty("relatedParty") List<PartyRef> relatedParty,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("characteristic") List<NameValue> characteristic,
        @JsonProperty("sendTime") OffsetDateTime sendTime,
        @JsonProperty("lastUpdate") OffsetDateTime lastUpdate,
        @JsonProperty("@type") String type) implements SendOutcome {
}
