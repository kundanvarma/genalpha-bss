package com.bss.campaign.client;

import java.util.List;

/** Who is in a segment — answered by the insight component, consent-aware. */
public interface InsightClient {

    List<SegmentMember> segmentMembers(String segment);

    /** Members of a saved audience (a rule tree), consent-aware. */
    List<SegmentMember> audienceMembers(String audienceId);
}
