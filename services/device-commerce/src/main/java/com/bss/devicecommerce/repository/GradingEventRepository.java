package com.bss.devicecommerce.repository;

import com.bss.devicecommerce.entity.GradingEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GradingEventRepository extends JpaRepository<GradingEvent, String> {

    List<GradingEvent> findByTenantIdAndValuationRefOrderByCreatedAtAsc(String tenantId, String valuationRef);
}
