package com.bss.intelligence.churn;

import com.bss.intelligence.exception.BadRequestException;
import com.bss.intelligence.security.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Where the scorer becomes production quality: outcomes label the feature
 * snapshots the sweep has been writing since day one, and the trainer fits
 * a per-tenant model from them ("it learns while live"). Operators with
 * history skip the wait: the import route trains on old data immediately.
 */
@Service
public class ChurnModelService {

    private static final int MIN_PER_CLASS = 10;

    private final ChurnFeatureSnapshotRepository snapshots;
    private final ChurnOutcomeRepository outcomes;
    private final ChurnModelRepository models;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public ChurnModelService(ChurnFeatureSnapshotRepository snapshots,
            ChurnOutcomeRepository outcomes,
            ChurnModelRepository models,
            TenantScope tenantScope, ObjectMapper objectMapper) {
        this.snapshots = snapshots;
        this.outcomes = outcomes;
        this.models = models;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ChurnLearning.OutcomeReceipt recordOutcome(ChurnLearning.RecordOutcomeRequest request) {
        if (request.party() == null || request.party().id() == null) {
            throw new BadRequestException("party {id} is required");
        }
        String tenant = tenantScope.currentTenantId();
        String partyId = request.party().id();
        ChurnOutcome outcome = outcomes.findByTenantIdAndPartyId(tenant, partyId)
                .orElseGet(() -> {
                    ChurnOutcome fresh = new ChurnOutcome();
                    fresh.setId(UUID.randomUUID().toString());
                    fresh.setTenantId(tenant);
                    fresh.setPartyId(partyId);
                    return fresh;
                });
        outcome.setChurned(!Boolean.FALSE.equals(request.churned()));
        outcome.setOccurredAt(OffsetDateTime.now());
        outcomes.save(outcome);
        return new ChurnLearning.OutcomeReceipt(new ChurnLearning.PartyRef(partyId), outcome.isChurned());
    }

    /** Train from what this deployment has lived through: snapshots + outcomes. */
    @Transactional
    public ChurnLearning.TrainingResult trainFromHistory() {
        String tenant = tenantScope.currentTenantId();
        Map<String, ChurnFeatureSnapshot> latestPerParty = new LinkedHashMap<>();
        for (ChurnFeatureSnapshot snap : snapshots.findByTenantIdOrderByTakenAtDesc(tenant)) {
            latestPerParty.putIfAbsent(snap.getPartyId(), snap);
        }
        List<double[]> x = new ArrayList<>();
        List<Boolean> y = new ArrayList<>();
        for (ChurnFeatureSnapshot snap : latestPerParty.values()) {
            boolean churned = outcomes.findByTenantIdAndPartyId(tenant, snap.getPartyId())
                    .map(ChurnOutcome::isChurned).orElse(false);
            x.add(snap.featureVector());
            y.add(churned);
        }
        return fitAndStore(tenant, x, y, "live-history");
    }

    /** Train on the operator's old data — production quality on day one. */
    @Transactional
    public ChurnLearning.TrainingResult trainFromImport(ChurnLearning.TrainFromImportRequest request) {
        List<ChurnLearning.TrainFromImportRequest.TrainingRow> rows = request.rows();
        if (rows == null || rows.isEmpty()) {
            throw new BadRequestException("rows [{features: [" + String.join(", ",
                    LogisticModel.FEATURES) + "], churned}] are required");
        }
        List<double[]> x = new ArrayList<>();
        List<Boolean> y = new ArrayList<>();
        for (ChurnLearning.TrainFromImportRequest.TrainingRow r : rows) {
            if (r == null || r.features() == null || r.features().size() != LogisticModel.FEATURES.length
                    || r.features().stream().anyMatch(java.util.Objects::isNull)) {
                throw new BadRequestException("each row needs features["
                        + LogisticModel.FEATURES.length + "] and churned");
            }
            x.add(r.features().stream().mapToDouble(Double::doubleValue).toArray());
            y.add(Boolean.TRUE.equals(r.churned()));
        }
        return fitAndStore(tenantScope.currentTenantId(), x, y, "imported-history");
    }

    @Transactional(readOnly = true)
    public ChurnLearning.ChurnModelStatus status() {
        String tenant = tenantScope.currentTenantId();
        Optional<ChurnModelRecord> model = models.findById(tenant);
        return new ChurnLearning.ChurnModelStatus(
                List.of(LogisticModel.FEATURES),
                snapshots.findByTenantIdOrderByTakenAtDesc(tenant).size(),
                outcomes.findByTenantId(tenant).size(),
                model.isPresent(),
                model.map(m -> m.getTrainedAt().toString()).orElse(null),
                model.map(ChurnModelRecord::getSampleCount).orElse(null),
                model.map(ChurnModelRecord::getPositives).orElse(null));
    }

    @Transactional(readOnly = true)
    public Optional<LogisticModel> modelFor(String tenant) {
        return models.findById(tenant).map(this::parse);
    }

    private ChurnLearning.TrainingResult fitAndStore(String tenant, List<double[]> x, List<Boolean> y,
            String source) {
        long positives = y.stream().filter(Boolean::booleanValue).count();
        long negatives = y.size() - positives;
        if (positives < MIN_PER_CLASS || negatives < MIN_PER_CLASS) {
            throw new BadRequestException("need at least " + MIN_PER_CLASS
                    + " churned and " + MIN_PER_CLASS + " retained examples; have "
                    + positives + "/" + negatives + " — keep collecting or import history");
        }
        boolean[] labels = new boolean[y.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = y.get(i);
        }
        LogisticModel model = LogisticModel.train(x.toArray(new double[0][]), labels);

        int correct = 0;
        for (int i = 0; i < labels.length; i++) {
            if ((model.predict(x.get(i)) >= 0.5) == labels[i]) {
                correct++;
            }
        }
        ChurnModelRecord record = models.findById(tenant).orElseGet(() -> {
            ChurnModelRecord fresh = new ChurnModelRecord();
            fresh.setTenantId(tenant);
            return fresh;
        });
        try {
            record.setParameters(objectMapper.writeValueAsString(Map.of(
                    "means", model.getMeans(), "stds", model.getStds(),
                    "weights", model.getWeights(), "bias", model.getBias())));
        } catch (Exception e) {
            throw new IllegalStateException("model serialization failed", e);
        }
        record.setSampleCount(y.size());
        record.setPositives((int) positives);
        record.setTrainedAt(OffsetDateTime.now());
        models.save(record);

        return new ChurnLearning.TrainingResult(true, source, y.size(), positives,
                BigDecimal.valueOf((double) correct / y.size()).setScale(3, java.math.RoundingMode.HALF_UP),
                List.of(LogisticModel.FEATURES));
    }

    @SuppressWarnings("unchecked")
    private LogisticModel parse(ChurnModelRecord record) {
        try {
            Map<String, Object> p = objectMapper.readValue(record.getParameters(), Map.class);
            return new LogisticModel(doubles(p.get("means")), doubles(p.get("stds")),
                    doubles(p.get("weights")), ((Number) p.get("bias")).doubleValue());
        } catch (Exception e) {
            throw new IllegalStateException("stored model unreadable", e);
        }
    }

    private static double[] doubles(Object value) {
        return ((List<Number>) value).stream().mapToDouble(Number::doubleValue).toArray();
    }
}
