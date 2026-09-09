package com.bss.intelligence.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A question the knowledge base could not answer — the content team's to-do list, counted. */
@Entity
@Table(name = "knowledge_gap")
public class KnowledgeGap {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "question", nullable = false, length = 500)
    private String question;

    @Column(name = "context", length = 120)
    private String context;

    @Column(name = "asked", nullable = false)
    private int asked;

    @Column(name = "first_asked", nullable = false)
    private OffsetDateTime firstAsked;

    @Column(name = "last_asked", nullable = false)
    private OffsetDateTime lastAsked;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getContext() { return context; }
    public void setContext(String context) { this.context = context; }
    public int getAsked() { return asked; }
    public void setAsked(int asked) { this.asked = asked; }
    public OffsetDateTime getFirstAsked() { return firstAsked; }
    public void setFirstAsked(OffsetDateTime firstAsked) { this.firstAsked = firstAsked; }
    public OffsetDateTime getLastAsked() { return lastAsked; }
    public void setLastAsked(OffsetDateTime lastAsked) { this.lastAsked = lastAsked; }
}
