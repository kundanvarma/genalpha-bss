package com.bss.party.repository;

import com.bss.party.entity.DirectoryExportRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DirectoryExportRunRepository extends JpaRepository<DirectoryExportRun, String> {

    Optional<DirectoryExportRun> findTopByTenantIdOrderByRanAtDesc(String tenantId);

    Optional<DirectoryExportRun> findByIdAndTenantId(String id, String tenantId);
}
