package com.bss.insight.repository;

import com.bss.insight.entity.DeskPreset;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeskPresetRepository extends JpaRepository<DeskPreset, String> {
    List<DeskPreset> findByTenantIdAndDeskAndFormOrderByCreatedAtDesc(String tenantId, String desk, String form);
    List<DeskPreset> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
