package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A segment sweep: how many of the segment's members were enrolled (newcomers only — enrolment is once). */
@JsonPropertyOrder({"journeyId", "segment", "enrolled"})
public record SegmentEnrollmentReceipt(String journeyId, String segment, int enrolled) {
}
