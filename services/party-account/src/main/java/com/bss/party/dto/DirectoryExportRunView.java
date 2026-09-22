package com.bss.party.dto;

import com.bss.party.entity.DirectoryExportRun;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;

/** One delta run's header — what DirectoryExportedEvent carries. */
@JsonPropertyOrder({"id", "ranAt", "rowCount", "deltaSince"})
public record DirectoryExportRunView(
        String id,
        String ranAt,
        int rowCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) String deltaSince) {

    public static DirectoryExportRunView of(DirectoryExportRun run, OffsetDateTime since) {
        return new DirectoryExportRunView(run.getId(), run.getRanAt().toString(), run.getRowCount(),
                since == null ? null : since.toString());
    }
}
