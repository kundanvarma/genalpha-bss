package com.bss.som.repository;

import com.bss.som.entity.ServiceRealisation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ServiceRealisationRepository extends JpaRepository<ServiceRealisation, String> {

    List<ServiceRealisation> findByTenantIdAndServiceIdOrderByRealisedAtAsc(String tenantId, String serviceId);
}
