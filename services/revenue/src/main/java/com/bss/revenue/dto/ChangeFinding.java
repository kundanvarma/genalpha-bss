package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * One thing validation found, said the way a controller would say it.
 * {@code blocks} stops the ladder; {@code warns} is a consequence the approver
 * is told about and signs for.
 */
@JsonPropertyOrder({"severity", "message"})
public record ChangeFinding(
        @JsonProperty("severity") String severity,
        @JsonProperty("message") String message) {

    public static final String BLOCKS = "blocks";
    public static final String WARNS = "warns";

    public static ChangeFinding blocks(String message) {
        return new ChangeFinding(BLOCKS, message);
    }

    public static ChangeFinding warns(String message) {
        return new ChangeFinding(WARNS, message);
    }

    public boolean blocking() {
        return BLOCKS.equals(severity);
    }

    /** One line of storage: severity, a tab, the sentence. */
    public String stored() {
        return severity + "\t" + message;
    }

    public static ChangeFinding parse(String line) {
        int tab = line.indexOf('\t');
        return tab < 0 ? warns(line) : new ChangeFinding(line.substring(0, tab), line.substring(tab + 1));
    }
}
