package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The public form submit. Consent arrives as true, "true" or "on" (a browser checkbox) — anything else captures nothing. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LeadForm(String name, String email, String consent, String utmSource) {

    public boolean consented() {
        return "true".equalsIgnoreCase(consent) || "on".equalsIgnoreCase(consent);
    }
}
