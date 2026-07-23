package com.bss.cart.repository;

import com.bss.cart.entity.AcpSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AcpSessionRepository extends JpaRepository<AcpSession, String> {

    Optional<AcpSession> findByIdAndTenantId(String id, String tenantId);
}
