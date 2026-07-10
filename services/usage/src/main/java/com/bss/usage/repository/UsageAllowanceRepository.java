package com.bss.usage.repository;

import com.bss.usage.entity.UsageAllowance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UsageAllowanceRepository extends JpaRepository<UsageAllowance, String> {

    List<UsageAllowance> findByProductOfferingIdAndUsageSpecName(String offeringId, String specName);
}
