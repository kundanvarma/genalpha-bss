package com.bss.som.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Field encryption for SIM PUKs: AES-256-GCM, a fresh IV per value, the ICCID
 * bound in as additional authenticated data so a ciphertext cannot be replayed
 * onto another card's row. The key arrives from the environment (a KMS/secret
 * store in production; a fixed dev key otherwise, loudly not for production).
 * AES-256 is already post-quantum comfortable — this closes a CLASSICAL gap
 * (plaintext card secrets at rest) flagged in docs/pqc-readiness.md.
 *
 * Legacy plaintext rows (pre-encryption) are read as-is and upgraded the next
 * time they are written; ciphertexts carry the "enc:v1:" prefix so the two
 * generations coexist during migration.
 */
@Component
public class PukVault {

    private static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public PukVault(@Value("${bss.sim.puk-key:}") String base64Key) {
        byte[] raw = base64Key == null || base64Key.isBlank()
                // dev fallback: deterministic so restarts can still decrypt —
                // production MUST set BSS_PUK_KEY (see helm values)
                ? "genalpha-dev-puk-key-not-for-prod!".getBytes(StandardCharsets.UTF_8)
                : Base64.getDecoder().decode(base64Key);
        byte[] sized = new byte[32];
        System.arraycopy(raw, 0, sized, 0, Math.min(raw.length, 32));
        this.key = new SecretKeySpec(sized, "AES");
    }

    public boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    public String encrypt(String puk, String iccid) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(iccid.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(puk.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PUK encryption failed", e);
        }
    }

    /** Decrypts an "enc:v1:" value; passes legacy plaintext through unchanged. */
    public String reveal(String stored, String iccid) {
        if (!isEncrypted(stored)) {
            return stored;
        }
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, java.util.Arrays.copyOfRange(in, 0, IV_BYTES)));
            cipher.updateAAD(iccid.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PUK decryption failed — wrong key or tampered row", e);
        }
    }
}
