package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** A customer signal at the front door — raw text in, the PII firewall runs before anything is stored. Context is the source's own document. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SignalInput(String source, String text, String sourceRef, String partyId, String channel, String lang,
        JsonNode context) {

    public static SignalInput of(String source, String text) {
        return new SignalInput(source, text, null, null, null, null, null);
    }

    public SignalInput withSource(String source) {
        return new SignalInput(source, text, sourceRef, partyId, channel, lang, context);
    }

    public SignalInput withSourceRef(String sourceRef) {
        return new SignalInput(source, text, sourceRef, partyId, channel, lang, context);
    }

    public SignalInput withPartyId(String partyId) {
        return new SignalInput(source, text, sourceRef, partyId, channel, lang, context);
    }

    public SignalInput withChannel(String channel) {
        return new SignalInput(source, text, sourceRef, partyId, channel, lang, context);
    }

    public SignalInput withLang(String lang) {
        return new SignalInput(source, text, sourceRef, partyId, channel, lang, context);
    }

    public SignalInput withContext(JsonNode context) {
        return new SignalInput(source, text, sourceRef, partyId, channel, lang, context);
    }
}
