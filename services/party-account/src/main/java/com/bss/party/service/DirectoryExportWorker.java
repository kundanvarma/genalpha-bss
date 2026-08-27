package com.bss.party.service;

import com.bss.party.security.TenantContext;
import com.bss.party.security.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily delta run the industry agreement asks for — one export per
 * tenant per clock tick. OFF by default: an export only makes sense once a
 * tenant has a directory-partner agreement, so a deployment opts in with
 * {@code bss.directory.export-enabled=true} (tests and demos use the
 * POST /directoryExport/run trigger instead).
 */
@Component
@ConditionalOnProperty(name = "bss.directory.export-enabled", havingValue = "true")
public class DirectoryExportWorker {

    private static final Logger log = LoggerFactory.getLogger(DirectoryExportWorker.class);

    private final DirectoryService directory;
    private final TenantRegistry tenants;

    public DirectoryExportWorker(DirectoryService directory, TenantRegistry tenants) {
        this.directory = directory;
        this.tenants = tenants;
    }

    @Scheduled(cron = "${bss.directory.export-cron:0 0 4 * * *}")
    public void exportAll() {
        for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
            try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
                directory.runExport();
            } catch (Exception e) {
                log.warn("directory export failed for tenant {}: {}", tenant.getId(), e.getMessage());
            }
        }
    }
}
