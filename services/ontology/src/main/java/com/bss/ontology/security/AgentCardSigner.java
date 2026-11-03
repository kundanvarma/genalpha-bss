package com.bss.ontology.security;

import com.bss.ontology.client.OntologyProperties;
import com.bss.ontology.dto.A2aMessages.Signature;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.util.Base64URL;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Signs the Agent Card (A2A 1.0 §8.4): a detached JWS over the card's RFC 8785
 * canonical form with {@code signatures} left out. The key is the deployment's
 * ({@code ONTOLOGY_A2A_SIGNING_KEY}, PEM PKCS#8 — EC P-256 signs ES256, RSA signs
 * RS256), never the repo's; without one the card is served unsigned rather than
 * signed with something made up. A key that is configured but unreadable stops
 * the service at start: an operator who asked for signed cards must not get
 * unsigned ones silently.
 */
@Component
public class AgentCardSigner {

    private final JWSSigner signer;
    private final JWSAlgorithm alg;
    private final String keyId;

    public AgentCardSigner(OntologyProperties props) {
        String pem = props.getA2a().getSigningKey();
        if (pem == null || pem.isBlank()) {
            this.signer = null;
            this.alg = null;
            this.keyId = null;
            return;
        }
        PrivateKey key = parse(pem);
        try {
            if (key instanceof ECPrivateKey ec) {
                this.signer = new ECDSASigner(ec);
                this.alg = JWSAlgorithm.ES256;
            } else if (key instanceof RSAPrivateKey rsa) {
                this.signer = new RSASSASigner(rsa);
                this.alg = JWSAlgorithm.RS256;
            } else {
                throw new IllegalStateException("ontology.a2a.signing-key is neither an EC nor an RSA private key");
            }
        } catch (JOSEException e) {
            throw new IllegalStateException("ontology.a2a.signing-key cannot sign: " + e.getMessage(), e);
        }
        String kid = props.getA2a().getSigningKeyId();
        this.keyId = kid == null || kid.isBlank() ? "agent-card" : kid.trim();
    }

    public boolean enabled() {
        return signer != null;
    }

    /** The signature over {@code card} (a tree without {@code signatures}), or empty when no key is configured. */
    public Optional<Signature> sign(JsonNode card) {
        if (signer == null) {
            return Optional.empty();
        }
        JWSHeader header = new JWSHeader.Builder(alg).type(JOSEObjectType.JOSE).keyID(keyId).build();
        Base64URL protectedHeader = header.toBase64URL();
        Base64URL payload = Base64URL.encode(canonical(card).getBytes(StandardCharsets.UTF_8));
        byte[] input = (protectedHeader + "." + payload).getBytes(StandardCharsets.US_ASCII);
        try {
            return Optional.of(new Signature(protectedHeader.toString(), signer.sign(header, input).toString()));
        } catch (JOSEException e) {
            throw new IllegalStateException("the Agent Card could not be signed: " + e.getMessage(), e);
        }
    }

    /**
     * RFC 8785 (JSON Canonicalization Scheme) for the values a card holds: object
     * members sorted by UTF-16 code units, no whitespace, strings escaped as
     * ECMAScript's JSON.stringify does. A card carries no fractional number; one
     * would be refused rather than spelled in a way a verifier might not repeat.
     */
    public static String canonical(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb);
        return sb.toString();
    }

    private static void write(JsonNode n, StringBuilder sb) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            sb.append("null");
        } else if (n.isObject()) {
            List<String> names = new ArrayList<>(n.propertyNames());
            names.sort(String::compareTo);   // String.compareTo IS UTF-16 code-unit order
            sb.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                string(names.get(i), sb);
                sb.append(':');
                write(n.get(names.get(i)), sb);
            }
            sb.append('}');
        } else if (n.isArray()) {
            sb.append('[');
            Iterator<JsonNode> it = n.values().iterator();
            boolean first = true;
            while (it.hasNext()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(it.next(), sb);
            }
            sb.append(']');
        } else if (n.isString()) {
            string(n.asString(), sb);
        } else if (n.isBoolean()) {
            sb.append(n.booleanValue());
        } else if (n.isIntegralNumber() && n.canConvertToLong()) {
            sb.append(n.longValue());
        } else {
            throw new IllegalArgumentException("a card value this canonicaliser does not spell: " + n.getNodeType());
        }
    }

    private static void string(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static PrivateKey parse(String pem) {
        String body = pem.replaceAll("-----BEGIN [A-Z ]*PRIVATE KEY-----", "")
                .replaceAll("-----END [A-Z ]*PRIVATE KEY-----", "")
                .replaceAll("\\\\n", "")
                .replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ontology.a2a.signing-key is not a PEM PKCS#8 private key", e);
        }
        for (String algorithm : List.of("EC", "RSA")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (Exception ignored) {
                // try the next family
            }
        }
        throw new IllegalStateException("ontology.a2a.signing-key is not a PKCS#8 EC or RSA private key");
    }
}
