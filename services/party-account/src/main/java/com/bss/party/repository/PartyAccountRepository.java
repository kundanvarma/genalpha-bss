package com.bss.party.repository;

import com.bss.party.entity.PartyAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PartyAccountRepository extends JpaRepository<PartyAccount, String> {
}
