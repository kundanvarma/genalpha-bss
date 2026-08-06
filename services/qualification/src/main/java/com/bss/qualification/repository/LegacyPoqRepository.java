package com.bss.qualification.repository;

import com.bss.qualification.entity.LegacyPoq;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LegacyPoqRepository extends JpaRepository<LegacyPoq, String> {

    List<LegacyPoq> findTop100ByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<LegacyPoq> findByIdAndTenantId(String id, String tenantId);
}
