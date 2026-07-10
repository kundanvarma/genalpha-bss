package com.bss.stock.repository;

import com.bss.stock.entity.ProductStock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductStockRepository extends JpaRepository<ProductStock, String> {

    Optional<ProductStock> findByIdAndTenantId(String id, String tenantId);

    List<ProductStock> findByTenantIdAndProductOfferingId(String tenantId, String productOfferingId);

    /**
     * Reservation reads the row under a write lock: two concurrent orders for
     * the last unit must serialise, not both succeed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ProductStock s where s.productOfferingId = :offeringId and s.tenantId = :tenantId")
    Optional<ProductStock> findForUpdateByProductOfferingId(@Param("offeringId") String offeringId,
            @Param("tenantId") String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ProductStock s where s.id = :id and s.tenantId = :tenantId")
    Optional<ProductStock> findForUpdateById(@Param("id") String id, @Param("tenantId") String tenantId);
}
