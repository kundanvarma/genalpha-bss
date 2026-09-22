package com.bss.quote.dto;

import com.bss.quote.entity.PipelineSnapshot;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** The open weighted forecast as it stood at one moment — forecast over time. */
@JsonPropertyOrder({"id", "capturedAt", "openCount", "openAmount", "weightedForecast", "currency"})
public record SnapshotView(String id, OffsetDateTime capturedAt, int openCount, BigDecimal openAmount,
        BigDecimal weightedForecast, String currency) {

    public static SnapshotView of(PipelineSnapshot s) {
        return new SnapshotView(s.getId(), s.getCapturedAt(), s.getOpenCount(), s.getOpenAmount(),
                s.getWeightedForecast(), s.getCurrency());
    }
}
