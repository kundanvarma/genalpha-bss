package com.bss.insight.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** Members plus WHICH path resolved them (sql | memory | prospect | visitor). */
@JsonPropertyOrder({"path", "count", "members"})
public record AudienceExplain(String path, int count, List<AudienceMember> members) {
}
