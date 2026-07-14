package com.bss.campaign.client;

import java.util.List;
import java.util.Map;

/** Who is in a segment — answered by the insight component, consent-aware. */
public interface InsightClient {

    List<Map<String, Object>> segmentMembers(String segment);
}
