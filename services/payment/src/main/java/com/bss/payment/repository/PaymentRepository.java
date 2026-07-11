package com.bss.payment.repository;

import com.bss.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    Optional<Payment> findByIdAndTenantId(String id, String tenantId);

    Optional<Payment> findFirstByTenantIdAndCorrelatorId(String tenantId, String correlatorId);
}
