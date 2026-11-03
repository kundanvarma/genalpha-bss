package com.bss.gateway.agent;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

/**
 * The fleet's stand-in for an agent operator, test scope only: a fresh Ed25519
 * key pair per instance (the private half never leaves this JVM) and a signer
 * that writes the three Web Bot Auth headers. The signature base is written out
 * by hand here, independently of the gateway's own builder, so a test that
 * passes proves the two agree rather than that the code agrees with itself.
 */
final class TestAgentSigner {

    final KeyPair pair;
    final String x;
    final String keyid;
    final String agentUrl;

    TestAgentSigner(String agentUrl) {
        try {
            this.pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        this.x = Ed25519Keys.xOf(pair.getPublic());
        this.keyid = Ed25519Keys.thumbprint(x);
        this.agentUrl = agentUrl;
    }

    /** The headers of a request to {@code authority}, signed with a window of [created, expires]. */
    Headers sign(String authority, long created, long expires) {
        String params = "(\"@authority\" \"signature-agent\";key=\"sig1\")"
                + ";created=" + created + ";expires=" + expires
                + ";keyid=\"" + keyid + "\";alg=\"ed25519\";tag=\"web-bot-auth\"";
        String base = "\"@authority\": " + authority + "\n"
                + "\"signature-agent\";key=\"sig1\": \"" + agentUrl + "\"\n"
                + "\"@signature-params\": " + params;
        return new Headers("sig1=" + params, "sig1=:" + sign(base) + ":", "sig1=\"" + agentUrl + "\"");
    }

    String sign(String base) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(pair.getPrivate());
            s.update(base.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    record Headers(String signatureInput, String signature, String signatureAgent) {
    }
}
