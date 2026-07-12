package com.bss.stock.repository;

import com.bss.stock.entity.ReserveProductStock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReserveProductStockRepository extends JpaRepository<ReserveProductStock, String> {

    Optional<ReserveProductStock> findByIdAndTenantId(String id, String tenantId);

    List<ReserveProductStock> findByTenantId(String tenantId);
}
