package com.bss.som.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * A REALISATION: the record that one resource-facing service was carried out
 * for a service — through which seam, by which vendor, with what external
 * reference. Written by the orchestrator at the moment it exercises the seam;
 * matched against the RFS the product spec's CFS declares. {@code rfsId} null
 * means the orchestrator did something the catalog never declared — the raw
 * material of the catalog-versus-code gate. Descriptive in step 2: nothing
 * reads this row to decide fulfilment.
 */
@Entity
@Table(name = "service_realisation")
public class ServiceRealisation {

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "service_id", nullable = false, length = 36)
    private String serviceId;

    /** The RFS spec (TMF633) the CFS declared for this seam; null = undeclared. */
    @Column(name = "rfs_id", length = 64)
    private String rfsId;

    @Column(name = "rfs_name", length = 160)
    private String rfsName;

    /** number | sim | ocs | slice | wholesale-access | partner-entitlement | cpe */
    @Column(name = "seam", nullable = false, length = 32)
    private String seam;

    /** The adapter that actually did it for this tenant, named honestly. */
    @Column(name = "vendor", length = 64)
    private String vendor;

    /** What the vendor handed back: MSISDN, ICCID, access-order id, entitlement code, rate plan. */
    @Column(name = "external_ref", length = 160)
    private String externalRef;

    /** The TMF634 resource specification the RFS names; null when none. */
    @Column(name = "resource_spec_id", length = 64)
    private String resourceSpecId;

    @Column(name = "resource_spec_name", length = 160)
    private String resourceSpecName;

    @Column(name = "realised_at", nullable = false)
    private OffsetDateTime realisedAt;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String serviceId) { this.serviceId = serviceId; }
    public String getRfsId() { return rfsId; }
    public void setRfsId(String rfsId) { this.rfsId = rfsId; }
    public String getRfsName() { return rfsName; }
    public void setRfsName(String rfsName) { this.rfsName = rfsName; }
    public String getSeam() { return seam; }
    public void setSeam(String seam) { this.seam = seam; }
    public String getVendor() { return vendor; }
    public void setVendor(String vendor) { this.vendor = vendor; }
    public String getExternalRef() { return externalRef; }
    public void setExternalRef(String externalRef) { this.externalRef = externalRef; }
    public String getResourceSpecId() { return resourceSpecId; }
    public void setResourceSpecId(String resourceSpecId) { this.resourceSpecId = resourceSpecId; }
    public String getResourceSpecName() { return resourceSpecName; }
    public void setResourceSpecName(String resourceSpecName) { this.resourceSpecName = resourceSpecName; }
    public OffsetDateTime getRealisedAt() { return realisedAt; }
    public void setRealisedAt(OffsetDateTime realisedAt) { this.realisedAt = realisedAt; }
}
