package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "What should this person see?" Without personalization consent only
 * {@code personalized:false}; with it the interests, the channel, the session
 * hero, and — when an operator rule matched — its banner, its experience block
 * (operator-authored keys such as {@code teaserOfferingId}, spread beside the
 * typed ones through {@code extensions}) and the rule's name.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"personalized", "interests", "channel", "segments", "heroCategory", "recentOfferings", "banner", "ruleName"})
public record Experience(boolean personalized, List<String> interests, String channel, List<String> segments,
        String heroCategory, List<String> recentOfferings, Object banner, String ruleName,
        @JsonAnyGetter Map<String, Object> extensions) {

    public Experience {
        extensions = extensions == null ? new LinkedHashMap<>() : extensions;
    }

    /** The unconsented answer: the default page, honestly marked. */
    public static Experience defaultPage() {
        return new Experience(false, null, null, null, null, null, null, null, null);
    }

    /** An operator-authored experience key, or null. */
    public Object extension(String key) {
        return extensions.get(key);
    }
}
