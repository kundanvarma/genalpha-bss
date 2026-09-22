package com.bss.quote.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The CDP's answer about an email: is it a known prospect, how far has it
 * engaged (none|opened|clicked), and does that count as engaged. {@link #NONE}
 * when the CDP is unreachable or nothing was asked — never blocks capture.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LeadSignal(Boolean knownProspect, String engagement, Boolean engaged) {

    public static final LeadSignal NONE = new LeadSignal(null, null, null);

    /** No signal at all — the CDP did not answer. */
    public boolean absent() {
        return knownProspect == null && engagement == null && engaged == null;
    }
}
