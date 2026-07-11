package com.bss.quote.repository;

import com.bss.quote.entity.Quote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface QuoteRepository extends JpaRepository<Quote, String> {

    Optional<Quote> findByIdAndTenantId(String id, String tenantId);

    List<Quote> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
