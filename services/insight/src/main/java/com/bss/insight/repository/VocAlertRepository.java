package com.bss.insight.repository;

import com.bss.insight.entity.VocAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VocAlertRepository extends JpaRepository<VocAlert, String> {

    boolean existsByTenantIdAndAspectAndIsoWeek(String tenantId, String aspect, String isoWeek);

    List<VocAlert> findTop20ByTenantIdOrderByCreatedAtDesc(String tenantId);
}
