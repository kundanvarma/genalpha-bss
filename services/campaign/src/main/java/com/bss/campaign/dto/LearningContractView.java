package com.bss.campaign.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** A decision point with its contract: the point's keys (re-labelled {@code LearningContract}), then the contract. */
@JsonPropertyOrder({"point", "contract"})
public record LearningContractView(@JsonUnwrapped DecisionPointView point, ContractView contract) {

    public static LearningContractView of(DecisionPointView point, ContractView contract) {
        return new LearningContractView(point.labelled("LearningContract"), contract);
    }
}
