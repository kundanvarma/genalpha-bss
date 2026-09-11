package com.bss.entitlement.eap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EAP-AKA (RFC 4187) as an Entitlement Configuration Server needs it: build
 * the EAP-Request/AKA-Challenge from an AUC vector, derive the keys, and
 * check the device's EAP-Response/AKA-Challenge (RES and AT_MAC). Pure JDK.
 *
 * Key derivation is RFC 4187 §7: MK = SHA-1(Identity | IK | CK), then the
 * FIPS 186-2 pseudo-random function (the "G" built from SHA-1's compression
 * function, no padding) stretched to 160 bytes → K_encr, K_aut, MSK, EMSK.
 */
public final class EapAka {

    public static final int CODE_REQUEST = 1;
    public static final int CODE_RESPONSE = 2;
    public static final int TYPE_AKA = 23;
    public static final int SUBTYPE_CHALLENGE = 1;
    public static final int AT_RAND = 1;
    public static final int AT_AUTN = 2;
    public static final int AT_RES = 3;
    public static final int AT_MAC = 11;

    private EapAka() {
    }

    /** K_encr / K_aut / MSK / EMSK for one session. */
    public record Keys(byte[] kEncr, byte[] kAut, byte[] msk, byte[] emsk) { }

    /** A parsed EAP-AKA packet. */
    public record Packet(int code, int identifier, int type, int subtype, Map<Integer, byte[]> attrs, byte[] raw) { }

    public static Keys deriveKeys(String identity, byte[] ik, byte[] ck) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(identity.getBytes(StandardCharsets.UTF_8));
            sha1.update(ik);
            sha1.update(ck);
            byte[] xkey = sha1.digest();
            byte[] stream = new byte[160];
            int off = 0;
            for (int j = 0; j < 4; j++) {
                for (int i = 0; i < 2; i++) {
                    byte[] block = new byte[64];
                    System.arraycopy(xkey, 0, block, 0, 20);
                    byte[] w = Sha1Block.compress(block);
                    System.arraycopy(w, 0, stream, off, 20);
                    off += 20;
                    xkey = add160(xkey, w, 1);
                }
            }
            return new Keys(Arrays.copyOfRange(stream, 0, 16), Arrays.copyOfRange(stream, 16, 32),
                    Arrays.copyOfRange(stream, 32, 96), Arrays.copyOfRange(stream, 96, 160));
        } catch (Exception e) {
            throw new IllegalStateException("EAP-AKA key derivation failed", e);
        }
    }

    /** (a + b + plus) mod 2^160, big-endian. */
    static byte[] add160(byte[] a, byte[] b, int plus) {
        byte[] out = new byte[20];
        int carry = plus;
        for (int i = 19; i >= 0; i--) {
            int s = (a[i] & 0xff) + (b[i] & 0xff) + carry;
            out[i] = (byte) (s & 0xff);
            carry = s >> 8;
        }
        return out;
    }

    /** EAP-Request/AKA-Challenge with AT_RAND, AT_AUTN and AT_MAC (over the packet, MAC zeroed). */
    public static byte[] challenge(int identifier, byte[] rand, byte[] autn, byte[] kAut) {
        byte[] body = concat(
                attr(AT_RAND, concat(new byte[2], rand)),
                attr(AT_AUTN, concat(new byte[2], autn)),
                attr(AT_MAC, new byte[18]));
        byte[] pkt = header(CODE_REQUEST, identifier, body);
        byte[] mac = hmacSha1_128(kAut, pkt);
        System.arraycopy(mac, 0, pkt, pkt.length - 16, 16);
        return pkt;
    }

    public static Packet parse(byte[] pkt) {
        if (pkt == null || pkt.length < 8) {
            throw new IllegalArgumentException("EAP packet too short");
        }
        int length = ((pkt[2] & 0xff) << 8) | (pkt[3] & 0xff);
        if (length != pkt.length) {
            throw new IllegalArgumentException("EAP length mismatch");
        }
        Map<Integer, byte[]> attrs = new LinkedHashMap<>();
        int i = 8;
        while (i + 2 <= pkt.length) {
            int type = pkt[i] & 0xff;
            int len = (pkt[i + 1] & 0xff) * 4;
            if (len < 2 || i + len > pkt.length) {
                throw new IllegalArgumentException("EAP attribute overruns packet");
            }
            attrs.put(type, Arrays.copyOfRange(pkt, i + 2, i + len));
            i += len;
        }
        return new Packet(pkt[0] & 0xff, pkt[1] & 0xff, pkt[4] & 0xff, pkt[5] & 0xff, attrs, pkt);
    }

    /** True when the packet's AT_MAC matches K_aut. */
    public static boolean verifyMac(Packet p, byte[] kAut) {
        byte[] raw = p.raw();
        int idx = -1;
        int i = 8;
        while (i + 2 <= raw.length) {
            int type = raw[i] & 0xff;
            int len = (raw[i + 1] & 0xff) * 4;
            if (type == AT_MAC) {
                idx = i;
                break;
            }
            i += len;
        }
        if (idx < 0) {
            return false;
        }
        byte[] copy = raw.clone();
        Arrays.fill(copy, idx + 4, idx + 20, (byte) 0);
        byte[] mac = hmacSha1_128(kAut, copy);
        return MessageDigest.isEqual(mac, Arrays.copyOfRange(raw, idx + 4, idx + 20));
    }

    /** The RES the device sent (AT_RES: 2 bytes of bit length, then RES). */
    public static byte[] res(Packet p) {
        byte[] v = p.attrs().get(AT_RES);
        if (v == null || v.length < 2) {
            return null;
        }
        int bits = ((v[0] & 0xff) << 8) | (v[1] & 0xff);
        int bytes = bits / 8;
        return bytes <= v.length - 2 ? Arrays.copyOfRange(v, 2, 2 + bytes) : null;
    }

    private static byte[] header(int code, int identifier, byte[] body) {
        byte[] pkt = new byte[8 + body.length];
        pkt[0] = (byte) code;
        pkt[1] = (byte) identifier;
        pkt[2] = (byte) (pkt.length >> 8);
        pkt[3] = (byte) pkt.length;
        pkt[4] = TYPE_AKA;
        pkt[5] = SUBTYPE_CHALLENGE;
        System.arraycopy(body, 0, pkt, 8, body.length);
        return pkt;
    }

    private static byte[] attr(int type, byte[] value) {
        byte[] out = new byte[2 + value.length];
        out[0] = (byte) type;
        out[1] = (byte) ((value.length + 2) / 4);
        System.arraycopy(value, 0, out, 2, value.length);
        return out;
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    static byte[] hmacSha1_128(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return Arrays.copyOf(mac.doFinal(data), 16);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA1 unavailable", e);
        }
    }

    /** SHA-1's compression function on one 64-byte block from the standard IV —
     * FIPS 186-2's G(t, c), which the JDK's padded digest cannot give us. */
    static final class Sha1Block {
        static byte[] compress(byte[] block) {
            int[] w = new int[80];
            for (int i = 0; i < 16; i++) {
                w[i] = ((block[i * 4] & 0xff) << 24) | ((block[i * 4 + 1] & 0xff) << 16)
                        | ((block[i * 4 + 2] & 0xff) << 8) | (block[i * 4 + 3] & 0xff);
            }
            for (int i = 16; i < 80; i++) {
                w[i] = Integer.rotateLeft(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
            }
            int a = 0x67452301, b = 0xEFCDAB89, c = 0x98BADCFE, d = 0x10325476, e = 0xC3D2E1F0;
            for (int i = 0; i < 80; i++) {
                int f;
                int k;
                if (i < 20) { f = (b & c) | (~b & d); k = 0x5A827999; }
                else if (i < 40) { f = b ^ c ^ d; k = 0x6ED9EBA1; }
                else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8F1BBCDC; }
                else { f = b ^ c ^ d; k = 0xCA62C1D6; }
                int t = Integer.rotateLeft(a, 5) + f + e + k + w[i];
                e = d; d = c; c = Integer.rotateLeft(b, 30); b = a; a = t;
            }
            int[] h = { 0x67452301 + a, 0xEFCDAB89 + b, 0x98BADCFE + c, 0x10325476 + d, 0xC3D2E1F0 + e };
            byte[] out = new byte[20];
            for (int i = 0; i < 5; i++) {
                out[i * 4] = (byte) (h[i] >>> 24);
                out[i * 4 + 1] = (byte) (h[i] >>> 16);
                out[i * 4 + 2] = (byte) (h[i] >>> 8);
                out[i * 4 + 3] = (byte) h[i];
            }
            return out;
        }
    }
}
