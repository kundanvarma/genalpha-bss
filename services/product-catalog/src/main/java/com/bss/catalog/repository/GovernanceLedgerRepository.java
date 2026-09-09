package com.bss.catalog.repository;

import com.bss.catalog.entity.GovernanceLedger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GovernanceLedgerRepository extends JpaRepository<GovernanceLedger, String> {

    List<GovernanceLedger> findAllByTenantIdAndOfferingIdOrderByAtAsc(String tenantId, String offeringId);
}
