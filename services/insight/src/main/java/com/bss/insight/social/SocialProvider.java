package com.bss.insight.social;

import com.bss.insight.dto.PublishedPost;
import com.bss.insight.dto.SocialMessage;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * The social platform seam. Every read returns the NORMALISED shape the desks
 * already consume, whatever the platform's wire format:
 * <ul>
 *   <li>mention / dm: {@link SocialMessage} {@code {id, platform, author, handle, text, created_time[, permalink]}}</li>
 *   <li>post: the platform's own post document {@code {id, message, created_time[, permalink]}}, passed to the wire verbatim</li>
 *   <li>lead: the Meta Lead Ads entry {@code {id, created_time, field_data:[{name, values}]}}, verbatim</li>
 * </ul>
 * Adding a platform (X, TikTok, LinkedIn) is a new implementation and a tenant
 * setting, never a change to listening, care, publishing or activation.
 */
public interface SocialProvider {

    String name();

    List<SocialMessage> mentions(SocialConfig cfg);

    List<SocialMessage> dms(SocialConfig cfg);

    /** @return the id and permalink of the published post */
    PublishedPost publish(SocialConfig cfg, String message);

    List<JsonNode> posts(SocialConfig cfg);

    /** Push SHA-256 hashed emails to a Custom Audience; @return rows accepted. */
    int pushAudience(SocialConfig cfg, String audienceId, List<String> hashedEmails);

    List<JsonNode> leads(SocialConfig cfg, String formId);
}
