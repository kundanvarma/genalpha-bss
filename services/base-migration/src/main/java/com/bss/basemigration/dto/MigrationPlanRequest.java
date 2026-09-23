package com.bss.basemigration.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The body that creates or edits a wave. A record cannot carry a field it
 * does not declare, so nothing on a posted document can reach a column the
 * desk does not own.
 *
 * <p>The four blocks are {@link JsonNode} because they ARE the operator's
 * documents: the service validates the few keys it acts on, fills the
 * defaults back in, and stores the rest as written. The two numbers stay
 * nodes too — the map only honoured an actual JSON number
 * ({@code instanceof Number}), so a posted {@code "5"} fell through to the
 * default, and a typed {@code Integer} would have started accepting it.
 * {@code PATCH} also needs to know which blocks were <em>mentioned</em>, not
 * only which were non-null, and an absent node is the only way to say that.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MigrationPlanRequest(
        JsonNode name,
        JsonNode matrix,
        JsonNode eligibility,
        JsonNode trigger,
        JsonNode jurisdictionPack,
        JsonNode maxOrdersPerRun,
        JsonNode breakerThreshold) {

    /** {@code map.get(k) != null}: present and not a JSON null. */
    public static boolean given(JsonNode node) {
        return node != null && !node.isNull();
    }

    /** {@code map.containsKey(k)}: mentioned at all, an explicit null included. */
    public static boolean mentioned(JsonNode node) {
        return node != null;
    }

    /** What {@code String.valueOf(map.get(k))} produced — "null" included, as it always was. */
    public static String text(JsonNode node) {
        return node == null || node.isNull() ? "null" : node.isTextual() ? node.textValue() : node.toString();
    }

    /** Did the substance of the plan change? Then a rehearsal receipt is stale. */
    public boolean touchesSubstance() {
        return mentioned(matrix) || mentioned(eligibility) || mentioned(trigger) || mentioned(jurisdictionPack);
    }
}
