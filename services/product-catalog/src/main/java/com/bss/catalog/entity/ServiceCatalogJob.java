package com.bss.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * TMF633 ImportJob / ExportJob — one row per requested job, the kind column
 * telling them apart. A job is a REQUEST: "load the catalog from this url" or
 * "write the catalog to this url". The record is kept faithfully (url, path,
 * contentType, query, status, dates, errorLog); no runner picks jobs up yet,
 * so a job stays in the "Not Started" state it was accepted in.
 */
@Entity
@Table(name = "service_catalog_job")
public class ServiceCatalogJob {

    public static final String IMPORT = "IMPORT";
    public static final String EXPORT = "EXPORT";

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "job_kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "href")
    private String href;

    @Column(name = "url", nullable = false, length = 1024)
    private String url;

    @Column(name = "path", length = 1024)
    private String path;

    @Column(name = "content_type")
    private String contentType;

    /** Export only: the selection the export should carry (a TMF630 filter string). */
    @Column(name = "query", length = 2000)
    private String query;

    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "error_log", length = 4000)
    private String errorLog;

    @Column(name = "creation_date")
    private OffsetDateTime creationDate;

    @Column(name = "completion_date")
    private OffsetDateTime completionDate;

    public ServiceCatalogJob() {
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getHref() {
        return href;
    }

    public void setHref(String href) {
        this.href = href;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorLog() {
        return errorLog;
    }

    public void setErrorLog(String errorLog) {
        this.errorLog = errorLog;
    }

    public OffsetDateTime getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(OffsetDateTime creationDate) {
        this.creationDate = creationDate;
    }

    public OffsetDateTime getCompletionDate() {
        return completionDate;
    }

    public void setCompletionDate(OffsetDateTime completionDate) {
        this.completionDate = completionDate;
    }
}
