package com.bss.quote.dto;

import com.bss.quote.entity.GuidedQuestion;
import com.bss.quote.entity.GuidedRecommendation;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** Guided selling: the questions a rep asks, the rules that answer them, and the answer. */
public final class GuidedSelling {

    private GuidedSelling() {
    }

    /** A questionnaire step. */
    @JsonPropertyOrder({"id", "questionKey", "prompt", "sortOrder"})
    public record QuestionView(String id, String questionKey, String prompt, int sortOrder) {

        public static QuestionView of(GuidedQuestion q) {
            return new QuestionView(q.getId(), q.getQuestionKey(), q.getPrompt(), q.getSortOrder());
        }
    }

    /** answer → offering × quantity. */
    @JsonPropertyOrder({"id", "questionKey", "answerValue", "offeringName", "quantity"})
    public record RecommendationRuleView(String id, String questionKey, String answerValue, String offeringName,
            int quantity) {

        public static RecommendationRuleView of(GuidedRecommendation r) {
            return new RecommendationRuleView(r.getId(), r.getQuestionKey(), r.getAnswerValue(),
                    r.getOfferingName(), r.getQuantity());
        }
    }

    /** What the answers add up to — one line per offering, quantities merged. */
    @JsonPropertyOrder({"recommendations"})
    public record Recommendations(List<Recommendation> recommendations) {
    }

    /** One recommended offering and the answer that earned it. */
    @JsonPropertyOrder({"offeringName", "quantity", "because"})
    public record Recommendation(String offeringName, int quantity, String because) {
    }
}
