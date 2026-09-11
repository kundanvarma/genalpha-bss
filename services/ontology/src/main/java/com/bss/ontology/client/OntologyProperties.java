package com.bss.ontology.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/** Where each component lives. A capability's route is relative to its component's base. */
@ConfigurationProperties(prefix = "ontology")
public class OntologyProperties {

    private String dir = "";
    private long downstreamTimeoutMs = 8000;
    private Map<String, String> components = new LinkedHashMap<>();
    private String receiptTopic = "bss.ontology.events";
    /** how long after an action its declared outcome is measured (the demo compresses this) */
    private int outcomeAfterDays = 90;

    public String getDir() { return dir; }
    public void setDir(String dir) { this.dir = dir; }
    public long getDownstreamTimeoutMs() { return downstreamTimeoutMs; }
    public void setDownstreamTimeoutMs(long downstreamTimeoutMs) { this.downstreamTimeoutMs = downstreamTimeoutMs; }
    public Map<String, String> getComponents() { return components; }
    public void setComponents(Map<String, String> components) { this.components = components; }
    public String getReceiptTopic() { return receiptTopic; }
    public void setReceiptTopic(String receiptTopic) { this.receiptTopic = receiptTopic; }
    public int getOutcomeAfterDays() { return outcomeAfterDays; }
    public void setOutcomeAfterDays(int outcomeAfterDays) { this.outcomeAfterDays = outcomeAfterDays; }

    /** The base URL of a component; unknown components fall back to the gateway, which routes by path. */
    public String baseOf(String component) {
        String url = components.get(component);
        if (url == null || url.isBlank()) {
            url = components.getOrDefault("gateway", "http://localhost:8080");
        }
        return url.replaceAll("/+$", "");
    }
}
