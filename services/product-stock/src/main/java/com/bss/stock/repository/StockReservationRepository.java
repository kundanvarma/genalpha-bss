package com.bss.stock.repository;

import com.bss.stock.entity.StockReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StockReservationRepository extends JpaRepository<StockReservation, String> {

    List<StockReservation> findByTenantIdAndOrderIdAndState(String tenantId, String orderId, String state);

    @Query("select coalesce(sum(r.quantity), 0) from StockReservation r "
            + "where r.productStockId = :stockId and r.state = 'active' and r.tenantId = :tenantId")
    int activeQuantityFor(@Param("stockId") String stockId, @Param("tenantId") String tenantId);
}
