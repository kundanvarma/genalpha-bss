package com.bss.party.repository;

import com.bss.party.entity.ErasureRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ErasureRecordRepository extends JpaRepository<ErasureRecord, String> {

    List<ErasureRecord> findTop50ByTenantIdOrderByExecutedAtDesc(String tenantId);

    List<ErasureRecord> findByTenantIdAndPartyId(String tenantId, String partyId);
}
