package com.bss.gateway.agent;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Ed25519 public keys from a JWK's {@code x}, their RFC 7638 / RFC 8037 thumbprint, and verification. */
final class Ed25519Keys {

    /** DER prefix of an X.509 SubjectPublicKeyInfo for Ed25519 (OID 1.3.101.112); the 32 raw bytes follow. */
    private static final byte[] SPKI_PREFIX = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};

    private Ed25519Keys() {
    }

    static PublicKey fromX(String x) {
        byte[] raw = Base64.getUrlDecoder().decode(x);
        if (raw.length != 32) {
            throw new IllegalArgumentException("an Ed25519 key is 32 bytes");
        }
        byte[] der = new byte[SPKI_PREFIX.length + 32];
        System.arraycopy(SPKI_PREFIX, 0, der, 0, SPKI_PREFIX.length);
        System.arraycopy(raw, 0, der, SPKI_PREFIX.length, 32);
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("not an Ed25519 key", e);
        }
    }

    /** The raw 32-byte public key as a JWK {@code x} (base64url, no padding). */
    static String xOf(PublicKey key) {
        byte[] der = key.getEncoded();
        byte[] raw = new byte[32];
        System.arraycopy(der, der.length - 32, raw, 0, 32);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** RFC 7638 thumbprint of an OKP key: SHA-256 over the required members in lexical order (RFC 8037 A.3). */
    static String thumbprint(String x) {
        String canonical = "{\"crv\":\"Ed25519\",\"kty\":\"OKP\",\"x\":\"" + x + "\"}";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean verify(PublicKey key, byte[] message, byte[] signature) {
        try {
            Signature v = Signature.getInstance("Ed25519");
            v.initVerify(key);
            v.update(message);
            return v.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
