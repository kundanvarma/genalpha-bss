package com.bss.billing.repository;

import com.bss.billing.entity.BillDispute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BillDisputeRepository extends JpaRepository<BillDispute, String> {

    boolean existsByTenantIdAndBillIdAndStatus(String tenantId, String billId, String status);

    java.util.Optional<BillDispute> findByIdAndTenantId(String id, String tenantId);

    List<BillDispute> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    java.util.Optional<BillDispute> findFirstByTenantIdAndBillIdOrderByCreatedAtDesc(
            String tenantId, String billId);
}
