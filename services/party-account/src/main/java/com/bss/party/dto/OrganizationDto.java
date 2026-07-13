package com.bss.party.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrganizationDto {

    @JsonProperty("id")
    private String id;

    @JsonProperty("href")
    private String href;

    @JsonProperty("name")
    private String name;

    @JsonProperty("tradingName")
    private String tradingName;

    @JsonProperty("parentOrganization")
    private java.util.Map<String, Object> parentOrganization;

    /** {value, unit}: monthly device co-pay the company covers per device. */
    @JsonProperty("deviceAllowance")
    private java.util.Map<String, Object> deviceAllowance;

    @JsonProperty("@type")
    private String type = "Organization";

    public OrganizationDto() {
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

    public String getTradingName() {
        return tradingName;
    }

    public void setTradingName(String tradingName) {
        this.tradingName = tradingName;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public java.util.Map<String, Object> getParentOrganization() { return parentOrganization; }
    public void setParentOrganization(java.util.Map<String, Object> parentOrganization) { this.parentOrganization = parentOrganization; }

    public java.util.Map<String, Object> getDeviceAllowance() { return deviceAllowance; }
    public void setDeviceAllowance(java.util.Map<String, Object> deviceAllowance) { this.deviceAllowance = deviceAllowance; }
}
