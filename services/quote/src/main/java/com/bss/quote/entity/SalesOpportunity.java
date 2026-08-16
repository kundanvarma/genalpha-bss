package com.bss.quote.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** TMF699 salesOpportunity: the revenue conversation a qualified lead
 * became — developed through pipeline stages until it is won (ideally with
 * a quote ref) or lost. Carries the value, close date, stage, probability
 * and owner a sales manager forecasts from. */
@Entity
@Table(name = "sales_opportunity")
public class SalesOpportunity {

    // TMF lifecycle state (the coarse status).
    public static final String DEVELOPED = "developed";
    public static final String WON = "won";
    public static final String LOST = "lost";

    // Pipeline stage (the board position). Default probability rides with it.
    public static final String QUALIFICATION = "qualification";
    public static final String NEEDS_ANALYSIS = "needsAnalysis";
    public static final String PROPOSAL = "proposal";
    public static final String NEGOTIATION = "negotiation";
    public static final String CLOSED_WON = "closedWon";
    public static final String CLOSED_LOST = "closedLost";

    /** The default win probability for a stage — overridable per deal. */
    public static int defaultProbability(String stage) {
        if (stage == null) return 10;
        return switch (stage) {
            case NEEDS_ANALYSIS -> 30;
            case PROPOSAL -> 50;
            case NEGOTIATION -> 75;
            case CLOSED_WON -> 100;
            case CLOSED_LOST -> 0;
            default -> 10; // qualification
        };
    }

    /** The pipeline stages a sales manager sees on the board, in order. */
    public static boolean isStage(String s) {
        return QUALIFICATION.equals(s) || NEEDS_ANALYSIS.equals(s) || PROPOSAL.equals(s)
                || NEGOTIATION.equals(s) || CLOSED_WON.equals(s) || CLOSED_LOST.equals(s);
    }

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "lead_id", length = 36)
    private String leadId;

    @Column(name = "state", nullable = false, length = 32)
    private String state;

    @Column(name = "quote_ref", length = 36)
    private String quoteRef;

    @Column(name = "amount", precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "expected_close_date")
    private LocalDate expectedCloseDate;

    @Column(name = "stage", length = 32)
    private String stage;

    @Column(name = "probability")
    private Integer probability;

    @Column(name = "owner_id", length = 64)
    private String ownerId;

    @Column(name = "owner_name", length = 255)
    private String ownerName;

    @Column(name = "close_reason", length = 255)
    private String closeReason;

    /** The account this deal is for, when it is with a party we already know
     *  (B2B expansion, an existing customer). Null for a pure prospect. When
     *  set, activities mirror onto that party's TMF683 360 timeline. */
    @Column(name = "party_id", length = 64)
    private String partyId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getHref() { return href; }
    public void setHref(String v) { this.href = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getLeadId() { return leadId; }
    public void setLeadId(String v) { this.leadId = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public String getQuoteRef() { return quoteRef; }
    public void setQuoteRef(String v) { this.quoteRef = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public LocalDate getExpectedCloseDate() { return expectedCloseDate; }
    public void setExpectedCloseDate(LocalDate v) { this.expectedCloseDate = v; }
    public String getStage() { return stage; }
    public void setStage(String v) { this.stage = v; }
    public Integer getProbability() { return probability; }
    public void setProbability(Integer v) { this.probability = v; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String v) { this.ownerId = v; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String v) { this.ownerName = v; }
    public String getCloseReason() { return closeReason; }
    public void setCloseReason(String v) { this.closeReason = v; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String v) { this.partyId = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
