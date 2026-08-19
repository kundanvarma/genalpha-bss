package com.bss.insight.repository;

import com.bss.insight.entity.SignalClassification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SignalClassificationRepository extends JpaRepository<SignalClassification, String> {

    Optional<SignalClassification> findByTenantIdAndSignalId(String tenantId, String signalId);

    List<SignalClassification> findByTenantIdAndSignalIdIn(String tenantId, Collection<String> signalIds);
}
