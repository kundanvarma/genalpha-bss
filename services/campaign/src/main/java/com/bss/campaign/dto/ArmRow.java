package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;

/** One A/B arm of a journey as the tuner sees it: its traffic weight, enrolments, conversions, rate (%) and revenue. */
@JsonPropertyOrder({"name", "weight", "enrolled", "converted", "rate", "revenue"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArmRow(String name, int weight, long enrolled, long converted, double rate, BigDecimal revenue) {
}
