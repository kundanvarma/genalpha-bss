package com.bss.insight.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * One thing a customer said, whatever the source. {@code text} is REDACTED
 * (the PII firewall runs at ingest, raw text is never persisted);
 * {@code redactions} is the audit of what was removed — types and counts,
 * never values. Still personal data: RLS + erasure by partyId apply.
 */
@Entity
@Table(name = "customer_signal")
public class CustomerSignal {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String source;

    @Column(name = "source_ref")
    private String sourceRef;

    @Column(name = "party_id")
    private String partyId;

    private String channel;

    private String lang;

    @Column(nullable = false, length = 4000)
    private String text;

    @Column(length = 2000)
    private String context;

    @Column(length = 500)
    private String redactions;

    @Column(name = "dedup_hash", nullable = false)
    private String dedupHash;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String v) { this.sourceRef = v; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String v) { this.partyId = v; }
    public String getChannel() { return channel; }
    public void setChannel(String v) { this.channel = v; }
    public String getLang() { return lang; }
    public void setLang(String v) { this.lang = v; }
    public String getText() { return text; }
    public void setText(String v) { this.text = v; }
    public String getContext() { return context; }
    public void setContext(String v) { this.context = v; }
    public String getRedactions() { return redactions; }
    public void setRedactions(String v) { this.redactions = v; }
    public String getDedupHash() { return dedupHash; }
    public void setDedupHash(String v) { this.dedupHash = v; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(OffsetDateTime v) { this.receivedAt = v; }
}
