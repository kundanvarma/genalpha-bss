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
    public Map<String, Object> recordOutcome(Map<String, Object> request) {
        if (!(request.get("party") instanceof Map<?, ?> party) || party.get("id") == null) {
            throw new BadRequestException("party {id} is required");
        }
        String tenant = tenantScope.currentTenantId();
        String partyId = String.valueOf(party.get("id"));
        ChurnOutcome outcome = outcomes.findByTenantIdAndPartyId(tenant, partyId)
                .orElseGet(() -> {
                    ChurnOutcome fresh = new ChurnOutcome();
                    fresh.setId(UUID.randomUUID().toString());
                    fresh.setTenantId(tenant);
                    fresh.setPartyId(partyId);
                    return fresh;
                });
        outcome.setChurned(!Boolean.FALSE.equals(request.get("churned")));
        outcome.setOccurredAt(OffsetDateTime.now());
        outcomes.save(outcome);
        return Map.of("party", Map.of("id", partyId), "churned", outcome.isChurned());
    }

    /** Train from what this deployment has lived through: snapshots + outcomes. */
    @Transactional
    public Map<String, Object> trainFromHistory() {
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
    public Map<String, Object> trainFromImport(Map<String, Object> request) {
        if (!(request.get("rows") instanceof List<?> rows) || rows.isEmpty()) {
            throw new BadRequestException("rows [{features: [" + String.join(", ",
                    LogisticModel.FEATURES) + "], churned}] are required");
        }
        List<double[]> x = new ArrayList<>();
        List<Boolean> y = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> r) || !(r.get("features") instanceof List<?> f)
                    || f.size() != LogisticModel.FEATURES.length) {
                throw new BadRequestException("each row needs features["
                        + LogisticModel.FEATURES.length + "] and churned");
            }
            x.add(f.stream().mapToDouble(v -> ((Number) v).doubleValue()).toArray());
            y.add(Boolean.TRUE.equals(r.get("churned")));
        }
        return fitAndStore(tenantScope.currentTenantId(), x, y, "imported-history");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status() {
        String tenant = tenantScope.currentTenantId();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("features", List.of(LogisticModel.FEATURES));
        status.put("snapshots", snapshots.findByTenantIdOrderByTakenAtDesc(tenant).size());
        status.put("labeledOutcomes", outcomes.findByTenantId(tenant).size());
        Optional<ChurnModelRecord> model = models.findById(tenant);
        status.put("trained", model.isPresent());
        model.ifPresent(m -> {
            status.put("trainedAt", m.getTrainedAt().toString());
            status.put("sampleCount", m.getSampleCount());
            status.put("positives", m.getPositives());
        });
        return status;
    }

    @Transactional(readOnly = true)
    public Optional<LogisticModel> modelFor(String tenant) {
        return models.findById(tenant).map(this::parse);
    }

    private Map<String, Object> fitAndStore(String tenant, List<double[]> x, List<Boolean> y,
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

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trained", true);
        result.put("source", source);
        result.put("sampleCount", y.size());
        result.put("positives", positives);
        result.put("trainingAccuracy", BigDecimal.valueOf((double) correct / y.size())
                .setScale(3, java.math.RoundingMode.HALF_UP));
        result.put("features", List.of(LogisticModel.FEATURES));
        return result;
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
