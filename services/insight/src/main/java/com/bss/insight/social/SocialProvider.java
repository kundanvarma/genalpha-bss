package com.bss.insight.social;

import java.util.List;
import java.util.Map;

/**
 * The social platform seam. Every method returns the NORMALISED shape the desks
 * already consume, whatever the platform's wire format:
 * <ul>
 *   <li>mention / dm: {@code {id, platform, author, handle, text, created_time[, permalink]}}</li>
 *   <li>post: {@code {id, message, created_time[, permalink]}}</li>
 *   <li>lead: Meta Lead Ads entry {@code {id, created_time, field_data:[{name, values}]}}</li>
 * </ul>
 * Adding a platform (X, TikTok, LinkedIn) is a new implementation and a tenant
 * setting, never a change to listening, care, publishing or activation.
 */
public interface SocialProvider {

    String name();

    List<Map<String, Object>> mentions(SocialConfig cfg);

    List<Map<String, Object>> dms(SocialConfig cfg);

    /** @return {@code {id, permalink}} of the published post */
    Map<String, Object> publish(SocialConfig cfg, String message);

    List<Map<String, Object>> posts(SocialConfig cfg);

    /** Push SHA-256 hashed emails to a Custom Audience; @return rows accepted. */
    int pushAudience(SocialConfig cfg, String audienceId, List<String> hashedEmails);

    List<Map<String, Object>> leads(SocialConfig cfg, String formId);
}
