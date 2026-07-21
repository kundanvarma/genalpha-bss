package com.bss.billing.repository;

import com.bss.billing.entity.BillingRunRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BillingRunRecordRepository extends JpaRepository<BillingRunRecord, String> {

    List<BillingRunRecord> findTop20ByTenantIdOrderByStartedAtDesc(String tenantId);

    List<BillingRunRecord> findByTenantIdAndStatus(String tenantId, String status);
}
