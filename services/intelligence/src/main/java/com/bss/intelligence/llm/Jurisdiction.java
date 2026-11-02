package com.bss.intelligence.llm;

import java.util.Locale;

/**
 * Where a model provider processes a prompt, as the ledger names it, and the
 * one residency rule a tenant can ask for (#422).
 *
 * The DEFAULT is derived from the provider kind, exactly as the governor has
 * always tagged it: anthropic = US, openai-compatible = self-hosted (that
 * dialect is how an operator reaches its own vLLM or Ollama), stub = none. An
 * operator who points openai-compatible at a hosted EU endpoint, or at a US
 * one, DECLARES it ({@code ai-jurisdiction: EU}); the declared word wins.
 * Only three words are understood — EU, US, self-hosted — and anything else
 * reads as "unknown", which an EU-only tenant refuses.
 */
public final class Jurisdiction {

    public static final String EU = "EU";
    public static final String US = "US";
    public static final String SELF_HOSTED = "self-hosted";
    public static final String NONE = "none";
    public static final String UNKNOWN = "unknown";

    private Jurisdiction() {
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
