package com.bss.ontology.dto;

import com.bss.ontology.service.Resolver;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * "May this happen, and if not, why?" — every precondition judged, the
 * permission clause that applied, the policy domain's word, and the first
 * refusal in the action's own words. The resolved objects ride along for the
 * executor and never reach the wire.
 */
@JsonPropertyOrder({"allowed", "refusal", "preconditions", "permission", "policy"})
public record Check(boolean allowed, @JsonInclude(JsonInclude.Include.NON_NULL) String refusal, List<Verdict> preconditions,
        PermissionVerdict permission, PolicyVerdict policy, @JsonIgnore Resolver.Resolved resolved) {
}
