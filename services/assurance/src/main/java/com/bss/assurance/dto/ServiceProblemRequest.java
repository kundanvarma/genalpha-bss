package com.bss.assurance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

/**
 * A problem DECLARED from outside the alarm loop. The originator block is the
 * declaring system's own document — stored and answered verbatim — so it stays
 * a tree; the door only insists that it IS an object and names a role, because
 * a problem is declared by someone.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServiceProblemRequest(JsonNode originatorParty, JsonNode name, JsonNode description,
                                    JsonNode affectedObject, JsonNode category,
                                    JsonNode priority, JsonNode reason) {

    public static final ServiceProblemRequest EMPTY =
            new ServiceProblemRequest(null, null, null, null, null, null, null);

    public JsonNode originator() {
        JsonNode block = Json.objectOrNull(originatorParty);
        return block != null && block.hasNonNull("role") ? block : null;
    }

    public String nameOrDescription() {
        return Json.set(name) ? Json.valueOf(name) : Json.valueOf(description);
    }

    public String descriptionValue() {
        return Json.valueOf(description);
    }

    public String affectedObjectOr(String fallback) {
        return Json.set(affectedObject) ? Json.valueOf(affectedObject) : fallback;
    }

    public String categoryOr(String fallback) {
        return Json.set(category) ? Json.valueOf(category) : fallback;
    }

    public String reasonOr(String fallback) {
        return Json.set(reason) ? Json.valueOf(reason) : fallback;
    }

    /**
     * A JSON number is taken as-is, a numeric string is parsed, and anything
     * else -- absent, null, or a word -- is the fallback.
     *
     * The last part used to be only half true: an absent key gave the fallback
     * but {@code "priority": "urgent"} threw NumberFormatException out of a
     * request mapper, which is a 500 for a body a caller could send by hand.
     * TMF656 types priority as an integer, so "urgent" is a malformed request
     * either way -- but this method's whole contract is to supply a value when
     * the field is unusable, and it did not honour that for the one case where
     * a caller controls the content rather than its presence.
     */
    public int priorityOr(int fallback) {
        if (priority != null && priority.isNumber()) {
            return priority.intValue();
        }
        if (!Json.set(priority)) {
            return fallback;
        }
        try {
            return Integer.parseInt(Json.valueOf(priority).strip());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }
}
