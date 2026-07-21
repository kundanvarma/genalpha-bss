package com.bss.cart.repository;

import com.bss.cart.entity.ShoppingCart;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ShoppingCartRepository extends JpaRepository<ShoppingCart, String> {

    Optional<ShoppingCart> findByIdAndTenantId(String id, String tenantId);

    // Deliberately NOT tenant-scoped: the abandonment sweeper is a system job
    // that spans every tenant's carts.
    List<ShoppingCart> findByStatusAndOwnerPartyIdNotNullAndLastUpdateBefore(
            String status, OffsetDateTime cutoff);

    java.util.List<ShoppingCart> findByTenantIdAndOwnerPartyId(String tenantId, String ownerPartyId);
}
