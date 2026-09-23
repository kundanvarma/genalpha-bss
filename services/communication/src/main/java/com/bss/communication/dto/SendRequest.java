package com.bss.communication.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * The martech door's body. {@code context} is the caller's own token document —
 * it stays an open map, because the renderer substitutes whatever it is handed;
 * everything the service decides on is declared.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SendRequest(
        @JsonProperty("subject") String subject,
        @JsonProperty("content") String content,
        @JsonProperty("messageType") String messageType,
        @JsonProperty("templateRef") String templateRef,
        @JsonProperty("locale") String locale,
        @JsonProperty("source") String source,
        @JsonProperty("toEmail") String toEmail,
        @JsonProperty("relatedParty") List<PartyRef> relatedParty,
        @JsonProperty("characteristic") List<NameValue> characteristic,
        @JsonProperty("context") Map<String, Object> context) {

    /** The receiver: the related party in the 'customer' role. */
    public String receiver() {
        if (relatedParty == null) {
            return null;
        }
        for (PartyRef p : relatedParty) {
            if (p != null && p.id() != null && "customer".equalsIgnoreCase(String.valueOf(p.role()))) {
                return p.id();
            }
        }
        return null;
    }

    /** TMF681 characteristic {name:"category", value:"transactional"} — the
     * caller's declaration that this send is service mail, not marketing. */
    public boolean transactional() {
        if (characteristic == null) {
            return false;
        }
        for (NameValue c : characteristic) {
            if (c != null && "category".equals(c.name())
                    && "transactional".equalsIgnoreCase(String.valueOf(c.value()))) {
                return true;
            }
        }
        return false;
    }
}
