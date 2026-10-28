package com.bss.benefit.service;

import java.util.Map;

/**
 * Law as data (ADR 0007) for benefit holds (#357): the longest a household's
 * Choice may be held by a benefit — a subsidised device promotion, say —
 * before it can be released or swapped. A tenant's benefit may ask for less,
 * never for more; the cap is the law of the country the tenant sells in.
 *
 * <ul>
 * <li>Default (EU/EEA): 24 months — the EECC's ceiling on a consumer's initial
 * commitment (Directive 2018/1972, Art. 105(1)).</li>
 * <li>Norway: 0 — whether a held benefit is a binding period (bindingstid)
 * under Norwegian consumer telecom rules is not yet answered, so no hold is
 * applied there until it is. Changing this is a decision with its source,
 * not a configuration edit.</li>
 * </ul>
 * These are interpretations a real engagement must have counsel confirm.
 */
final class HoldLaw {

    static final int DEFAULT_MAX_MONTHS = 24;
    private static final Map<String, Integer> MAX_MONTHS_BY_COUNTRY = Map.of("NO", 0);

    private HoldLaw() {
    }

    /** The longest hold the law of {@code country} allows (null or unknown: the EU default). */
    static int maxMonths(String country) {
        return country == null ? DEFAULT_MAX_MONTHS
                : MAX_MONTHS_BY_COUNTRY.getOrDefault(country.trim().toUpperCase(), DEFAULT_MAX_MONTHS);
    }

    /** What actually applies: the benefit's own hold, capped by the law. */
    static int effectiveMonths(Integer asked, String country) {
        return asked == null || asked <= 0 ? 0 : Math.min(asked, maxMonths(country));
    }
}
