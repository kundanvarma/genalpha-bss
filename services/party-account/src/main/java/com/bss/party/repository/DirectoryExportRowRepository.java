package com.bss.party.repository;

import com.bss.party.entity.DirectoryExportRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DirectoryExportRowRepository extends JpaRepository<DirectoryExportRow, String> {

    List<DirectoryExportRow> findByTenantIdAndRunId(String tenantId, String runId);
}
