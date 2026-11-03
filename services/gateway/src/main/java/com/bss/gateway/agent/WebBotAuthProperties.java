package com.bss.gateway.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Which agent operators this deployment recognises when their agents sign
 * requests (Web Bot Auth, #427). Empty by default: an operator is trusted only
 * because someone running this deployment wrote it here. Each operator has
 * static keys, a key directory URL, or both. The gateway only ever fetches the
 * directory URLs written here — never a URL a request names.
 *
 * <pre>
 * bss.web-bot-auth.operators:
 *   - name: example-agents
 *     signature-agent: https://agents.example.com      # what its agents put in Signature-Agent
 *     directory: https://agents.example.com/.well-known/http-message-signatures-directory
 *     keys:                                             # optional static JWKs (public)
 *       - { kty: OKP, crv: Ed25519, x: "..." }
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "bss.web-bot-auth")
public class WebBotAuthProperties {

    /** The draft recommends expires no more than 24 hours after created; we refuse longer. */
    private Duration maxValidity = Duration.ofHours(24);
    /** Tolerance for the agent's clock in both directions — skew, not policy. */
    private Duration clockSkew = Duration.ofSeconds(60);
    /** How long a fetched directory's keys are trusted before it must be fetched again. */
    private Duration directoryTtl = Duration.ofHours(1);
    /** The fastest a directory is re-fetched, however many unknown key ids arrive. */
    private Duration directoryMinRefresh = Duration.ofSeconds(60);
    /** A plain-http directory is refused unless this is set (a laptop stand-in only). */
    private boolean allowInsecureDirectories = false;
    private List<Operator> operators = new ArrayList<>();

    public Duration getMaxValidity() {
        return maxValidity;
    }

    public void setMaxValidity(Duration maxValidity) {
        this.maxValidity = maxValidity;
    }

    public Duration getClockSkew() {
        return clockSkew;
    }

    public void setClockSkew(Duration clockSkew) {
        this.clockSkew = clockSkew;
    }

    public Duration getDirectoryTtl() {
        return directoryTtl;
    }

    public void setDirectoryTtl(Duration directoryTtl) {
        this.directoryTtl = directoryTtl;
    }

    public Duration getDirectoryMinRefresh() {
        return directoryMinRefresh;
    }

    public void setDirectoryMinRefresh(Duration directoryMinRefresh) {
        this.directoryMinRefresh = directoryMinRefresh;
    }

    public boolean isAllowInsecureDirectories() {
        return allowInsecureDirectories;
    }

    public void setAllowInsecureDirectories(boolean allowInsecureDirectories) {
        this.allowInsecureDirectories = allowInsecureDirectories;
    }

    public List<Operator> getOperators() {
        return operators;
    }

    public void setOperators(List<Operator> operators) {
        this.operators = operators;
    }

    /** One agent operator: the name downstream sees, where its keys come from. */
    public static class Operator {
        private String name;
        private String signatureAgent;
        private String directory;
        private List<Jwk> keys = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSignatureAgent() {
            return signatureAgent;
        }

        public void setSignatureAgent(String signatureAgent) {
            this.signatureAgent = signatureAgent;
        }

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }

        public List<Jwk> getKeys() {
            return keys;
        }

        public void setKeys(List<Jwk> keys) {
            this.keys = keys;
        }
    }

    /** A public JSON Web Key; only OKP / Ed25519 is used. */
    public static class Jwk {
        private String kty;
        private String crv;
        private String x;

        public String getKty() {
            return kty;
        }

        public void setKty(String kty) {
            this.kty = kty;
        }

        public String getCrv() {
            return crv;
        }

        public void setCrv(String crv) {
            this.crv = crv;
        }

        public String getX() {
            return x;
        }

        public void setX(String x) {
            this.x = x;
        }
    }
}
