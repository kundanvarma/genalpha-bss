package com.bss.ordering.repository;

import com.bss.ordering.entity.ProductOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductOrderRepository extends JpaRepository<ProductOrder, String> {

    Optional<ProductOrder> findByIdAndTenantId(String id, String tenantId);

    /** A member's orders in one category — the allowance ledger reads these. */
    java.util.List<ProductOrder> findByTenantIdAndOwnerPartyIdAndCategory(
            String tenantId, String ownerPartyId, String category);

    /** A member's orders awaiting family approval. */
    java.util.List<ProductOrder> findByTenantIdAndOwnerPartyIdAndState(
            String tenantId, String ownerPartyId, String state);
}
