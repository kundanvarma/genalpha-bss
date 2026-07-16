package com.bss.billing.repository;

import com.bss.billing.entity.BillFormatProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BillFormatProfileRepository extends JpaRepository<BillFormatProfile, String> {

    Optional<BillFormatProfile> findByTenantIdAndCode(String tenantId, String code);

    List<BillFormatProfile> findByTenantIdOrderByCode(String tenantId);
}
