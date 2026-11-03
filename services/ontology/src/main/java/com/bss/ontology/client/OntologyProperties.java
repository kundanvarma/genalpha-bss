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
    /**
     * A governed action must leave a receipt, so this service refuses to start
     * when it cannot write one. True is the application default — see
     * ReceiptPublisher for the incident that made it one. Setting it false is a
     * deliberate choice to run actions that cannot be proved.
     */
    private boolean receiptsRequired = true;
    /** The A2A server face (#426): which skills a partner's agent may delegate, and how the Agent Card is signed. */
    private A2a a2a = new A2a();

    public A2a getA2a() { return a2a; }
    public void setA2a(A2a a2a) { this.a2a = a2a; }

    /**
     * The A2A door. {@code executeEnabled} is FALSE by default: a partner's agent
     * may ask (dry run, explain) and nothing more until an operator opens the one
     * executing skill — which then runs through the same execute path, receipts and
     * autonomy gate as every other caller. The signing key is a deployment secret
     * (PEM, PKCS#8, EC P-256 or RSA) read from the environment, never from the repo;
     * empty = the card is served unsigned and says nothing about a signature.
     */
    public static class A2a {
        private boolean executeEnabled = false;
        private String signingKey = "";
        private String signingKeyId = "";

        public boolean isExecuteEnabled() { return executeEnabled; }
        public void setExecuteEnabled(boolean executeEnabled) { this.executeEnabled = executeEnabled; }
        public String getSigningKey() { return signingKey; }
        public void setSigningKey(String signingKey) { this.signingKey = signingKey; }
        public String getSigningKeyId() { return signingKeyId; }
        public void setSigningKeyId(String signingKeyId) { this.signingKeyId = signingKeyId; }
    }

    public String getDir() { return dir; }
    public void setDir(String dir) { this.dir = dir; }
    public long getDownstreamTimeoutMs() { return downstreamTimeoutMs; }
    public void setDownstreamTimeoutMs(long downstreamTimeoutMs) { this.downstreamTimeoutMs = downstreamTimeoutMs; }
    public Map<String, String> getComponents() { return components; }
    public void setComponents(Map<String, String> components) { this.components = components; }
    public String getReceiptTopic() { return receiptTopic; }
    public void setReceiptTopic(String receiptTopic) { this.receiptTopic = receiptTopic; }
    public boolean isReceiptsRequired() { return receiptsRequired; }
    public void setReceiptsRequired(boolean receiptsRequired) { this.receiptsRequired = receiptsRequired; }
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
