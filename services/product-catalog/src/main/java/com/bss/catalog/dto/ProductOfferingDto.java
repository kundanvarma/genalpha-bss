package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProductOfferingDto {

    @JsonProperty("id")
    private String id;

    @JsonProperty("href")
    private String href;

    @NotBlank(message = "name is required")
    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("lifecycleStatus")
    private String lifecycleStatus;

    @JsonProperty("validFor")
    private java.util.Map<String, String> validFor;

    @JsonProperty("version")
    private String version;

    @JsonProperty("lastUpdate")
    private OffsetDateTime lastUpdate;

    @JsonProperty("productSpecification")
    private EntityRef productSpecification;

    @JsonProperty("isBundle")
    private Boolean isBundle;

    @JsonProperty("requiresVerifiedIdentity")
    private Boolean requiresVerifiedIdentity;

    /** House field beside TMF620 (#354): Choices this plan grants the household. */
    @JsonProperty("choicesGranted")
    private Integer choicesGranted;

    /** House field beside TMF620 (#354): what this benefit costs, in Choices. */
    @JsonProperty("choiceCost")
    private Integer choiceCost;

    /** House field beside TMF620 (#357): months a picked benefit holds its Choice (a subsidised device offer). */
    @JsonProperty("choiceHoldMonths")
    private Integer choiceHoldMonths;

    /** House field beside TMF620 (#359): the partner who provides this benefit. */
    @JsonProperty("choicePartner")
    private String choicePartner;

    /** House field beside TMF620 (#359): where the partner's own terms are (a link or a short text). */
    @JsonProperty("choicePartnerTerms")
    private String choicePartnerTerms;

    /** House field beside TMF620 (#359): what the operator expects to pay the partner a month per active benefit, in the tenant's currency. */
    @JsonProperty("choiceWholesaleCost")
    private java.math.BigDecimal choiceWholesaleCost;

    @JsonProperty("bundledProductOffering")
    private List<Map<String, Object>> bundledProductOffering;

    @JsonProperty("category")
    private List<Map<String, Object>> category;

    @JsonProperty("productOfferingPrice")
    private List<Map<String, Object>> productOfferingPrice;

    @JsonProperty("productOfferingTerm")
    private List<Map<String, Object>> productOfferingTerm;

    @JsonProperty("productOfferingRelationship")
    private List<Map<String, Object>> productOfferingRelationship;

    @JsonProperty("channel")
    private java.util.List<java.util.Map<String, Object>> channel;

    @JsonProperty("attachment")
    private List<Map<String, Object>> attachment;

    @JsonProperty("@type")
    private String type = "ProductOffering";

    public ProductOfferingDto() {
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLifecycleStatus() {
        return lifecycleStatus;
    }

    public void setLifecycleStatus(String lifecycleStatus) {
        this.lifecycleStatus = lifecycleStatus;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    public EntityRef getProductSpecification() {
        return productSpecification;
    }

    public void setProductSpecification(EntityRef productSpecification) {
        this.productSpecification = productSpecification;
    }

    public Boolean getIsBundle() {
        return isBundle;
    }

    public void setIsBundle(Boolean isBundle) {
        this.isBundle = isBundle;
    }

    public Boolean getRequiresVerifiedIdentity() {
        return requiresVerifiedIdentity;
    }

    public void setRequiresVerifiedIdentity(Boolean requiresVerifiedIdentity) {
        this.requiresVerifiedIdentity = requiresVerifiedIdentity;
    }

    public Integer getChoicesGranted() {
        return choicesGranted;
    }

    public void setChoicesGranted(Integer choicesGranted) {
        this.choicesGranted = choicesGranted;
    }

    public String getChoicePartner() {
        return choicePartner;
    }

    public void setChoicePartner(String choicePartner) {
        this.choicePartner = choicePartner;
    }

    public String getChoicePartnerTerms() {
        return choicePartnerTerms;
    }

    public void setChoicePartnerTerms(String choicePartnerTerms) {
        this.choicePartnerTerms = choicePartnerTerms;
    }

    public java.math.BigDecimal getChoiceWholesaleCost() {
        return choiceWholesaleCost;
    }

    public void setChoiceWholesaleCost(java.math.BigDecimal choiceWholesaleCost) {
        this.choiceWholesaleCost = choiceWholesaleCost;
    }

    public Integer getChoiceHoldMonths() {
        return choiceHoldMonths;
    }

    public void setChoiceHoldMonths(Integer choiceHoldMonths) {
        this.choiceHoldMonths = choiceHoldMonths;
    }

    public Integer getChoiceCost() {
        return choiceCost;
    }

    public void setChoiceCost(Integer choiceCost) {
        this.choiceCost = choiceCost;
    }

    public List<Map<String, Object>> getBundledProductOffering() {
        return bundledProductOffering;
    }

    public void setBundledProductOffering(List<Map<String, Object>> bundledProductOffering) {
        this.bundledProductOffering = bundledProductOffering;
    }

    public List<Map<String, Object>> getCategory() {
        return category;
    }

    public void setCategory(List<Map<String, Object>> category) {
        this.category = category;
    }

    public List<Map<String, Object>> getProductOfferingPrice() {
        return productOfferingPrice;
    }

    public void setProductOfferingPrice(List<Map<String, Object>> productOfferingPrice) {
        this.productOfferingPrice = productOfferingPrice;
    }

    public List<Map<String, Object>> getProductOfferingTerm() {
        return productOfferingTerm;
    }

    public void setProductOfferingTerm(List<Map<String, Object>> productOfferingTerm) {
        this.productOfferingTerm = productOfferingTerm;
    }

    public List<Map<String, Object>> getAttachment() {
        return attachment;
    }

    public void setAttachment(List<Map<String, Object>> attachment) {
        this.attachment = attachment;
    }

    public java.util.Map<String, String> getValidFor() { return validFor; }
    public void setValidFor(java.util.Map<String, String> v) { this.validFor = v; }

    public java.util.List<java.util.Map<String, Object>> getChannel() { return channel; }
    public void setChannel(java.util.List<java.util.Map<String, Object>> v) { this.channel = v; }

    public List<Map<String, Object>> getProductOfferingRelationship() {
        return productOfferingRelationship;
    }

    public void setProductOfferingRelationship(List<Map<String, Object>> productOfferingRelationship) {
        this.productOfferingRelationship = productOfferingRelationship;
    }
}
