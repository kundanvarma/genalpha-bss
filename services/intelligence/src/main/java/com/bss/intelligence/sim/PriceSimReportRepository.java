package com.bss.intelligence.sim;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PriceSimReportRepository extends JpaRepository<PriceSimReport, String> {
    List<PriceSimReport> findTop50ByTenantIdOrderByCreatedAtDesc(String tenantId);
}
