package com.bss.devicecommerce.repository;

import com.bss.devicecommerce.entity.TradeInValuation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TradeInValuationRepository extends JpaRepository<TradeInValuation, String> {

    Optional<TradeInValuation> findByIdAndTenantId(String id, String tenantId);

    List<TradeInValuation> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
