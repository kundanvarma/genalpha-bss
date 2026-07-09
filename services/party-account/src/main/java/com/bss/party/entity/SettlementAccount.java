package com.bss.party.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "settlement_account")
public class SettlementAccount {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "name", nullable = false)
    private String name;

    /** JSON array of related parties, echoed verbatim. */
    @Column(name = "related_party", length = 4000)
    private String relatedPartyJson;

    public SettlementAccount() {
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRelatedPartyJson() {
        return relatedPartyJson;
    }

    public void setRelatedPartyJson(String relatedPartyJson) {
        this.relatedPartyJson = relatedPartyJson;
    }
}
