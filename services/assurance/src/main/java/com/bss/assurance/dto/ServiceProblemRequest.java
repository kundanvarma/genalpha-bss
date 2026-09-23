package com.bss.assurance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

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

    /** A JSON number was taken as-is; anything else was parsed, and an absent key meant 2. */
    public int priorityOr(int fallback) {
        if (priority != null && priority.isNumber()) {
            return priority.intValue();
        }
        return Json.set(priority) ? Integer.parseInt(Json.valueOf(priority)) : fallback;
    }
}
