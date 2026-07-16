package com.bss.party.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import jakarta.validation.constraints.NotBlank;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class IndividualDto {

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @com.fasterxml.jackson.annotation.JsonProperty("billingAnchorDay")
    private Integer billingAnchorDay;
    @com.fasterxml.jackson.annotation.JsonProperty("billDelivery")
    private String billDelivery;

    @JsonProperty("id")
    private String id;

    @JsonProperty("href")
    private String href;

    @JsonProperty("givenName")
    private String givenName;

    @NotBlank(message = "familyName is required")
    @JsonProperty("familyName")
    private String familyName;

    @JsonProperty("contactMedium")
    private List<Map<String, Object>> contactMedium;

    @JsonProperty("organization")
    private Map<String, Object> organization;

    @JsonProperty("birthDate")
    private String birthDate;

    /** Read-only projection: {id, status} of the person who pays for this
     * individual. Written ONLY through the consent endpoints, never PATCH. */
    @JsonProperty("householdPayer")
    private Map<String, Object> householdPayer;

    @JsonProperty("@type")
    private String type = "Individual";

    public IndividualDto() {
    }

    public String getId() {
        return id;
    }

    public String getBirthDate() { return birthDate; }
    public void setBirthDate(String birthDate) { this.birthDate = birthDate; }

    public void setId(String id) {
        this.id = id;
    }

    public String getHref() {
        return href;
    }

    public void setHref(String href) {
        this.href = href;
    }

    public String getGivenName() {
        return givenName;
    }

    public void setGivenName(String givenName) {
        this.givenName = givenName;
    }

    public String getFamilyName() {
        return familyName;
    }

    public void setFamilyName(String familyName) {
        this.familyName = familyName;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<Map<String, Object>> getContactMedium() {
        return contactMedium;
    }

    public void setContactMedium(List<Map<String, Object>> contactMedium) {
        this.contactMedium = contactMedium;
    }

    public Map<String, Object> getOrganization() { return organization; }
    public void setOrganization(Map<String, Object> organization) { this.organization = organization; }
    public Map<String, Object> getHouseholdPayer() { return householdPayer; }
    public void setHouseholdPayer(Map<String, Object> v) { this.householdPayer = v; }

    public Integer getBillingAnchorDay() { return billingAnchorDay; }
    public void setBillingAnchorDay(Integer v) { this.billingAnchorDay = v; }
    public String getBillDelivery() { return billDelivery; }
    public void setBillDelivery(String v) { this.billDelivery = v; }
}
