package com.bss.porting.gateway;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Country-specific portability rules — the part that genuinely varies by
 * jurisdiction: the number format, and how long the regulator allows the
 * cutover to take. Norway (Nkom) mandates fast porting (next business day
 * class); other regimes differ. Extend the map to add a country.
 */
public final class PortingRules {

    public record Rule(Pattern numberFormat, int cutoverHours, String regulator) {
    }

    private static final Map<String, Rule> BY_COUNTRY = Map.of(
            "NO", new Rule(Pattern.compile("^\\+47\\d{8}$"), 24, "Nkom (via NRDB)"),
            "SE", new Rule(Pattern.compile("^\\+46\\d{7,9}$"), 24, "PTS"),
            "GB", new Rule(Pattern.compile("^\\+44\\d{9,10}$"), 24, "Ofcom"),
            "US", new Rule(Pattern.compile("^\\+1\\d{10}$"), 24, "FCC (via NPAC)"));

    private static final Rule DEFAULT =
            new Rule(Pattern.compile("^\\+\\d{6,15}$"), 48, "national regulator");

    private PortingRules() {
    }

    public static Rule forCountry(String country) {
        return BY_COUNTRY.getOrDefault(country == null ? "" : country.toUpperCase(), DEFAULT);
    }

    public static boolean numberValid(String country, String number) {
        return number != null && forCountry(country).numberFormat().matcher(number).matches();
    }

    public static OffsetDateTime cutoverFor(String country) {
        return OffsetDateTime.now().plusHours(forCountry(country).cutoverHours());
    }
}
