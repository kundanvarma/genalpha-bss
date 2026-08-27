package com.bss.devicecommerce.repository;

import com.bss.devicecommerce.entity.TradeInResidual;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TradeInResidualRepository extends JpaRepository<TradeInResidual, String> {

    Optional<TradeInResidual> findByIdAndTenantId(String id, String tenantId);

    List<TradeInResidual> findByTenantIdOrderByDeviceRefAscAgeMonthsAsc(String tenantId);

    List<TradeInResidual> findByTenantIdAndDeviceRefOrderByAgeMonthsAsc(String tenantId, String deviceRef);
}
