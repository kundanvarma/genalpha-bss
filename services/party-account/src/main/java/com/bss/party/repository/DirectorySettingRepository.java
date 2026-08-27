package com.bss.party.repository;

import com.bss.party.entity.DirectorySetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface DirectorySettingRepository extends JpaRepository<DirectorySetting, String> {

    List<DirectorySetting> findByTenantIdAndPartyId(String tenantId, String partyId);

    List<DirectorySetting> findByTenantId(String tenantId);

    List<DirectorySetting> findByTenantIdAndUpdatedAtAfter(String tenantId, OffsetDateTime after);
}
