package com.bss.insight.service;

import com.bss.insight.dto.ActivationJobView;
import com.bss.insight.entity.ActivationJob;
import com.bss.insight.repository.ActivationJobRepository;
import com.bss.insight.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The transactional units of an activation job, each committed on its own so the
 * lifecycle is OBSERVABLE to a poller: queued -> running -> done|error. (The
 * orchestration + the slow platform push live in {@link ActivationService},
 * outside any transaction.)
 */
@Service
public class ActivationJobService {

    private final ActivationJobRepository jobs;
    private final TenantScope tenantScope;

    public ActivationJobService(ActivationJobRepository jobs, TenantScope tenantScope) {
        this.jobs = jobs;
        this.tenantScope = tenantScope;
    }

    @Transactional
    public String createQueued(String audienceId, String externalAudienceId, String mode, String destination) {
        ActivationJob j = new ActivationJob();
        j.setId(UUID.randomUUID().toString());
        j.setTenantId(tenantScope.currentTenantId());
        j.setAudienceId(audienceId);
        j.setExternalAudienceId(externalAudienceId);
        j.setMode(mode);
        j.setDestination(destination);
        j.setStatus(ActivationJob.QUEUED);
        j.setCreatedAt(OffsetDateTime.now());
        jobs.save(j);
        return j.getId();
    }

    @Transactional
    public void markRunning(String jobId) {
        ActivationJob j = load(jobId);
        j.setStatus(ActivationJob.RUNNING);
        jobs.save(j);
    }

    @Transactional
    public void complete(String jobId, int members, int pushed, int skipped) {
        ActivationJob j = load(jobId);
        j.setStatus(ActivationJob.DONE);
        j.setMembers(members);
        j.setPushed(pushed);
        j.setSkipped(skipped);
        j.setFinishedAt(OffsetDateTime.now());
        jobs.save(j);
    }

    @Transactional
    public void fail(String jobId, String error) {
        ActivationJob j = load(jobId);
        j.setStatus(ActivationJob.ERROR);
        j.setError(error == null ? "activation failed" : error.substring(0, Math.min(error.length(), 480)));
        j.setFinishedAt(OffsetDateTime.now());
        jobs.save(j);
    }

    @Transactional(readOnly = true)
    public ActivationJobView status(String jobId) {
        return view(load(jobId));
    }

    private ActivationJob load(String jobId) {
        return jobs.findByIdAndTenantId(jobId, tenantScope.currentTenantId())
                .orElseThrow(() -> new IllegalArgumentException("activation job not found: " + jobId));
    }

    static ActivationJobView view(ActivationJob j) {
        return new ActivationJobView(j.getId(), j.getAudienceId(), j.getExternalAudienceId(), j.getMode(),
                j.getDestination(), j.getStatus(), j.getMembers(), j.getPushed(), j.getSkipped(), j.getError(),
                j.getFinishedAt());
    }
}
