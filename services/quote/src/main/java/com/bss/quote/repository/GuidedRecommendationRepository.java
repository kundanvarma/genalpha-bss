package com.bss.quote.repository;

import com.bss.quote.entity.GuidedRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GuidedRecommendationRepository extends JpaRepository<GuidedRecommendation, String> {
    List<GuidedRecommendation> findByTenantIdOrderByCreatedAt(String tenantId);
}
