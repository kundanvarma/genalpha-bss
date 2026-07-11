package com.bss.recommendation.rank;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** The transparent v1 order: bundles first, catalog order otherwise. */
@Component
@ConditionalOnProperty(name = "bss.recommendation.ranker", havingValue = "rules")
public class RulesRanker implements Ranker {

    @Override
    public List<Map<String, Object>> rank(List<Map<String, Object>> candidates) {
        return candidates.stream()
                .sorted((a, b) -> Boolean.compare(
                        !Boolean.TRUE.equals(a.get("isBundle")),
                        !Boolean.TRUE.equals(b.get("isBundle"))))
                .toList();
    }
}
