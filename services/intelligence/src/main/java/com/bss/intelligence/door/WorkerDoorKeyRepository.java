package com.bss.intelligence.door;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkerDoorKeyRepository extends JpaRepository<WorkerDoorKey, String> {

    Optional<WorkerDoorKey> findByKeyHash(String keyHash);

    Optional<WorkerDoorKey> findByIdAndTenantId(String id, String tenantId);
}
