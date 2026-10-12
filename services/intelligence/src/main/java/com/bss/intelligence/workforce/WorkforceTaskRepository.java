package com.bss.intelligence.workforce;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkforceTaskRepository extends JpaRepository<WorkforceTask, String> {

    Optional<WorkforceTask> findByIdAndTenantId(String id, String tenantId);

    List<WorkforceTask> findByTenantIdAndStatus(String tenantId, String status);

    /** The detail scan: recent rows, for per-worker and per-kind breakdowns. */
    List<WorkforceTask> findTop200ByTenantIdOrderByLastUpdateDesc(String tenantId);

    /* TRUE TOTALS. The breakdowns above are a 200-row window ordered by
     * lastUpdate, which is right for "who is working now" and wrong for "how
     * much has been done": a task whose lease expires updates again and can
     * push an older row out of the window, so a count taken from it moves in
     * BOTH directions. Observed 12 Oct — completed went 48 -> 47 -> 49 while
     * the ledger recorded 39 tasks worked. These count every row. */
    long countByTenantIdAndStatus(String tenantId, String status);

    long countByTenantIdAndStatusAndSelfTokensIsNotNull(String tenantId, String status);
}
