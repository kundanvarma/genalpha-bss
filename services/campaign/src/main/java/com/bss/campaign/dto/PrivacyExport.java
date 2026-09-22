package com.bss.campaign.dto;

import com.bss.campaign.entity.JourneyEnrollment;
import com.bss.campaign.entity.MarketingTouch;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** A person's marketing shadow: their journey enrolments and every recorded touch. */
@JsonPropertyOrder({"category", "count", "items"})
public record PrivacyExport(String category, int count, Items items) {

    @JsonPropertyOrder({"journeyEnrollments", "marketingTouches"})
    public record Items(List<JourneyEnrollment> journeyEnrollments, List<MarketingTouch> marketingTouches) {
    }
}
