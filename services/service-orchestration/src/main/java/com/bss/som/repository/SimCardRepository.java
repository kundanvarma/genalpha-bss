package com.bss.som.repository;

import com.bss.som.entity.SimCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SimCardRepository extends JpaRepository<SimCard, String> {

    Optional<SimCard> findFirstByTenantIdAndServiceId(String tenantId, String serviceId);

    Optional<SimCard> findFirstByTenantIdAndServiceIdAndStatus(
            String tenantId, String serviceId, String status);
}
