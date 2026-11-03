package com.bss.intelligence.llm;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * Where a model provider processes a prompt, as the ledger names it, and the
 * one residency rule a tenant can ask for (#422).
 *
 * The ENDPOINT decides first: a call that goes to a provider's known public
 * endpoint ({@code api.openai.com}, {@code api.anthropic.com}) is US whatever
 * the provider kind and whatever was declared — a declaration cannot move a
 * public endpoint. A blank base URL is the dialect's public endpoint, because
 * that is where the adapter sends the call. Otherwise the operator's DECLARED
 * word wins ({@code ai-jurisdiction: EU}), and failing that the provider
 * kind's default: anthropic = US, openai-compatible = self-hosted (that
 * dialect is how an operator reaches its own vLLM or Ollama), stub = none.
 * Only three words are understood — EU, US, self-hosted — and anything else
 * reads as "unknown", which an EU-only tenant refuses.
 */
public final class Jurisdiction {

    public static final String EU = "EU";
    public static final String US = "US";
    public static final String SELF_HOSTED = "self-hosted";
    public static final String NONE = "none";
    public static final String UNKNOWN = "unknown";

    /** Where each dialect's adapter sends a call when no base URL is configured. */
    public static final String ANTHROPIC_PUBLIC = "https://api.anthropic.com";
    public static final String OPENAI_PUBLIC = "https://api.openai.com/v1";

    /** Public endpoints whose jurisdiction is a fact about the host, not an operator's word. */
    private static final Map<String, String> PUBLIC_ENDPOINTS = Map.of(
            "api.openai.com", US,
            "api.anthropic.com", US);

    private Jurisdiction() {
    }

    /** The URL a call really goes to: the configured base, else the dialect's public endpoint. */
    public static String effectiveUrl(String provider, String baseUrl) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl.trim();
        }
        return switch (provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT)) {
            case "anthropic" -> ANTHROPIC_PUBLIC;
            case "openai", "openai-compatible" -> OPENAI_PUBLIC;
            default -> null;
        };
    }

    /** The jurisdiction of a known public endpoint, or null when the URL's host is not one. */
    public static String ofEndpoint(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.trim();
        try {
            String host = URI.create(u.contains("://") ? u : "https://" + u).getHost();
            return host == null ? null : PUBLIC_ENDPOINTS.get(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The jurisdiction of a call to this provider at this base URL (blank = the
     * dialect's public endpoint): a known public endpoint first, then the
     * declared word, then the provider kind's default. The stub sends nothing.
     */
    public static String of(String declared, String provider, String baseUrl) {
        if ("stub".equalsIgnoreCase(provider == null ? "" : provider.trim())) {
            return NONE;
        }
        String known = ofEndpoint(effectiveUrl(provider, baseUrl));
        return known != null ? known : of(declared, provider);
    }

    /** Today's tag for a provider kind, used when the operator declared nothing. */
    public static String defaultFor(String provider) {
        return switch (provider == null ? "" : provider) {
            case "anthropic" -> US;
            case "openai-compatible" -> SELF_HOSTED;
            case "stub" -> NONE;
            default -> UNKNOWN;
        };
    }

    /** An operator's declared jurisdiction, normalised; null when nothing was declared. */
    public static String declared(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "eu" -> EU;
            case "us" -> US;
            case "self-hosted" -> SELF_HOSTED;
            default -> UNKNOWN;
        };
    }

    /** The declared word if there is one, else the provider kind's default. */
    public static String of(String declared, String provider) {
        String d = declared(declared);
        return d != null ? d : defaultFor(provider);
    }

    /**
     * A tenant's data-residency setting ({@code ai-data-residency}). Absent or
     * blank is ANY — today's behaviour. {@code eu} admits only EU and
     * self-hosted providers (and the stub, which sends nothing anywhere). Any
     * other word is INVALID and refuses every call: an operator who wrote a
     * residency rule meant to restrict, so a typo must not quietly mean "any".
     */
    public enum Residency {
        ANY, EU_ONLY, INVALID;

        public static Residency of(String raw) {
            if (raw == null || raw.isBlank()) {
                return ANY;
            }
            return "eu".equalsIgnoreCase(raw.trim()) ? EU_ONLY : INVALID;
        }

        public boolean permits(String jurisdiction) {
            return switch (this) {
                case ANY -> true;
                case EU_ONLY -> EU.equals(jurisdiction) || SELF_HOSTED.equals(jurisdiction)
                        || NONE.equals(jurisdiction);
                case INVALID -> false;
            };
        }
    }
}
