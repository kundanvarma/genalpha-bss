package com.bss.party.repository;

import com.bss.party.entity.RegistryFeedCursor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RegistryFeedCursorRepository extends JpaRepository<RegistryFeedCursor, String> {

    Optional<RegistryFeedCursor> findByTenantId(String tenantId);
}
