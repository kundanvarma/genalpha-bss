package com.bss.cart.repository;

import com.bss.cart.entity.ShoppingCart;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface ShoppingCartRepository extends JpaRepository<ShoppingCart, String> {

    List<ShoppingCart> findByStatusAndOwnerPartyIdNotNullAndLastUpdateBefore(
            String status, OffsetDateTime cutoff);
}
