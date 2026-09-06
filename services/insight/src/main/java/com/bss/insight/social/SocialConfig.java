package com.bss.insight.social;

/**
 * One tenant's social line, resolved at call time. {@code provider} picks the
 * adapter ('mock' — the normalised dev shape; 'meta' — the Graph API);
 * {@code accountId} is the brand's page id (Meta) or handle (mock);
 * {@code adsToken} is the Marketing-API credential for Custom Audiences and
 * falls back to the page token when empty.
 */
public record SocialConfig(String provider, String apiUrl, String apiVersion, String accountId,
        String igUserId, String accessToken, String adsToken) {

    private static boolean has(String v) {
        return v != null && !v.isBlank();
    }

    /** Listening, care and publishing need a url AND a page/handle. */
    public boolean enabled() {
        return has(apiUrl) && has(accountId);
    }

    /** Audience activation needs only a url (the audience id names the target). */
    public boolean audiencesEnabled() {
        return has(apiUrl);
    }

    public String providerName() {
        return has(provider) ? provider.trim().toLowerCase() : "mock";
    }

    public String versionOrDefault() {
        return has(apiVersion) ? apiVersion.trim() : "v21.0";
    }

    public String adsTokenOrPage() {
        return has(adsToken) ? adsToken : accessToken;
    }
}
