package com.bss.paymentmethod.repository;

import com.bss.paymentmethod.entity.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, String> {

    Optional<PaymentMethod> findByIdAndTenantId(String id, String tenantId);

    List<PaymentMethod> findByTenantIdAndOwnerPartyIdAndStatus(String tenantId, String ownerPartyId, String status);
}
