package com.bss.revenue.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** The MRR waterfall, computed from the subledger's own recurring-revenue rows. */
@JsonPropertyOrder({"period", "accountCode", "note", "months", "drillDown", "@type"})
public record SubscriptionMetricsView(
        @JsonProperty("period") Period period,
        @JsonProperty("accountCode") String accountCode,
        @JsonProperty("note") String note,
        @JsonProperty("months") List<MonthRow> months,
        @JsonProperty("drillDown") List<DrillRow> drillDown,
        @JsonProperty("@type") String type) {
}
