package com.bss.intelligence.churn;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ChurnFeatureSnapshotRepository extends JpaRepository<ChurnFeatureSnapshot, String> {

    boolean existsByTenantIdAndPartyIdAndSnapshotDate(String tenantId, String partyId, LocalDate date);

    List<ChurnFeatureSnapshot> findByTenantIdOrderByTakenAtDesc(String tenantId);
}
