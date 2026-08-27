package com.bss.devicecommerce.repository;

import com.bss.devicecommerce.entity.DeviceAgreement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceAgreementRepository extends JpaRepository<DeviceAgreement, String> {

    Optional<DeviceAgreement> findByIdAndTenantId(String id, String tenantId);

    List<DeviceAgreement> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<DeviceAgreement> findByTenantIdAndOrderRef(String tenantId, String orderRef);

    List<DeviceAgreement> findByTenantIdAndSubscriptionRefAndStatus(String tenantId, String subscriptionRef,
            String status);
}
