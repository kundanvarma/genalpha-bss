package com.bss.campaign.client;

import java.util.List;
import java.util.Map;

/** Who is in a segment — answered by the insight component, consent-aware. */
public interface InsightClient {

    List<Map<String, Object>> segmentMembers(String segment);

    /** Members of a saved audience (a rule tree), consent-aware. */
    List<Map<String, Object>> audienceMembers(String audienceId);
}
