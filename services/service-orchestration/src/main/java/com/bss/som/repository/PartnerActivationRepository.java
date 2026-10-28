package com.bss.som.repository;

import com.bss.som.entity.PartnerActivation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PartnerActivationRepository extends JpaRepository<PartnerActivation, String> {

    Optional<PartnerActivation> findByTenantIdAndServiceId(String tenantId, String serviceId);

    List<PartnerActivation> findTop100ByTenantIdAndStateInOrderByLastUpdateAsc(String tenantId, Collection<String> states);
}
