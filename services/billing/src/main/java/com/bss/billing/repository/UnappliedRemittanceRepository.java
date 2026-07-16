package com.bss.billing.repository;

import com.bss.billing.entity.UnappliedRemittance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UnappliedRemittanceRepository extends JpaRepository<UnappliedRemittance, String> {

    List<UnappliedRemittance> findTop100ByTenantIdOrderByReceivedAtDesc(String tenantId);
}
