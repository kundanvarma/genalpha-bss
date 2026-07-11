package com.bss.recommendation.rank;

import java.util.List;
import java.util.Map;

/**
 * The learning seam of TMF680: candidates in, an order out. The API and the
 * channels never change when the ranking gets smarter — a real ML model
 * (feature store, offline training, whatever the operator runs) is just
 * another implementation of this interface, selected by configuration
 * ("bss.recommendation.ranker").
 */
public interface Ranker {

    List<Map<String, Object>> rank(List<Map<String, Object>> candidates);
}
