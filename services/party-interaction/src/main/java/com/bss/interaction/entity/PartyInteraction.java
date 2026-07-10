package com.bss.interaction.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "party_interaction")
public class PartyInteraction {

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "description")
    private String description;

    @Column(name = "channel")
    private String channel;

    @Column(name = "direction")
    private String direction;

    @Column(name = "status")
    private String status;

    @Column(name = "customer_party_id")
    private String customerPartyId;

    @Column(name = "agent_id")
    private String agentId;

    @Column(name = "org_id")
    private String orgId;

    @Column(name = "interaction_date")
    private OffsetDateTime interactionDate;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public PartyInteraction() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHref() {
        return href;
    }

    public void setHref(String href) {
        this.href = href;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCustomerPartyId() {
        return customerPartyId;
    }

    public void setCustomerPartyId(String customerPartyId) {
        this.customerPartyId = customerPartyId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getOrgId() {
        return orgId;
    }

    public void setOrgId(String orgId) {
        this.orgId = orgId;
    }

    public OffsetDateTime getInteractionDate() {
        return interactionDate;
    }

    public void setInteractionDate(OffsetDateTime interactionDate) {
        this.interactionDate = interactionDate;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }
}
