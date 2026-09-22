package com.bss.intelligence.churn;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/** The churn learning loop's bodies and receipts: an outcome in, a training
 * run out, the model's status. */
public final class ChurnLearning {

    private ChurnLearning() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"id"})
    public record PartyRef(String id) {
    }

    /** POST /churnOutcome — this customer left (or provably stayed). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecordOutcomeRequest(PartyRef party, Boolean churned) {
    }

    @JsonPropertyOrder({"party", "churned"})
    public record OutcomeReceipt(PartyRef party, boolean churned) {
    }

    /** POST /churnTraining/import — the operator's history, one row per customer. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TrainFromImportRequest(List<TrainingRow> rows) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record TrainingRow(List<Double> features, Boolean churned) {
        }
    }

    @JsonPropertyOrder({"trained", "source", "sampleCount", "positives", "trainingAccuracy", "features"})
    public record TrainingResult(boolean trained, String source, int sampleCount, long positives,
            BigDecimal trainingAccuracy, List<String> features) {
    }

    /** GET /churnModel — what the tenant's model has to learn from, and when it last did. */
    @JsonPropertyOrder({"features", "snapshots", "labeledOutcomes", "trained", "trainedAt", "sampleCount",
            "positives"})
    public record ChurnModelStatus(List<String> features, int snapshots, int labeledOutcomes, boolean trained,
            @JsonInclude(JsonInclude.Include.NON_NULL) String trainedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer sampleCount,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer positives) {
    }

    /** POST /churnSweep — alerts raised across every tenant. */
    @JsonPropertyOrder({"alerts"})
    public record ChurnSweepResult(int alerts) {
    }
}
