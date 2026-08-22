package com.bss.billing.repository;

import com.bss.billing.entity.MigrationRehearsal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MigrationRehearsalRepository extends JpaRepository<MigrationRehearsal, String> {
    List<MigrationRehearsal> findTop50ByTenantIdOrderByCreatedAtDesc(String tenantId);
}
