package com.bss.qualification.repository;

import com.bss.qualification.entity.CoverageMap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CoverageMapRepository extends JpaRepository<CoverageMap, String> {

    List<CoverageMap> findByTenantId(String tenantId);

    Optional<CoverageMap> findByIdAndTenantId(String id, String tenantId);

    List<CoverageMap> findByTenantIdAndTechnologyAndPostcodePrefix(
            String tenantId, String technology, String postcodePrefix);
}
