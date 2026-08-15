package com.bss.insight.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** SHA-256 hex of a normalized (trimmed, lowercased) identifier — the contract
 * every ad platform expects, so PII never leaves in clear. */
public final class Hashing {
    private Hashing() { }

    public static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
