package com.bss.party.repository;

import com.bss.party.entity.PartyAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PartyAccountRepository extends JpaRepository<PartyAccount, String> {

    Optional<PartyAccount> findByIdAndTenantId(String id, String tenantId);
}
