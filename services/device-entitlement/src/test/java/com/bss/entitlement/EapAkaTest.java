package com.bss.entitlement;

import com.bss.entitlement.eap.EapAka;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-implementation check: the same identity / IK / CK (3GPP TS 35.208
 * test set 1) must give the same RFC 4187 keys and the byte-identical
 * EAP-Request/AKA-Challenge as the Node implementation the HSS mock and the
 * device simulator share (integrations/mock-hss/milenage.js).
 */
class EapAkaTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final String IDENTITY = "0234150999999999@nai.epc.mnc015.mcc234.3gppnetwork.org";
    private static final byte[] IK = HEX.parseHex("f769bcd751044604127672711c6d3441");
    private static final byte[] CK = HEX.parseHex("b40ba9a3c58b2a05bbf0d987b21bf8cb");
    private static final byte[] RAND = HEX.parseHex("23553cbe9637a89d218ae64dae47bf35");
    private static final byte[] AUTN = HEX.parseHex("55f328b43577000d0dd7d6b9b607f8cb");

    @Test
    void keysMatchTheNodeImplementation() {
        EapAka.Keys keys = EapAka.deriveKeys(IDENTITY, IK, CK);
        assertThat(HEX.formatHex(keys.kEncr())).isEqualTo("e75bb66d6a09e0ae6b93db0c9fcbf4a1");
        assertThat(HEX.formatHex(keys.kAut())).isEqualTo("20585ce0302fafb7c457061516149268");
        assertThat(HEX.formatHex(keys.msk()).startsWith("68c107ba03b00f78")).isTrue();
    }

    @Test
    void challengeIsByteIdenticalAndItsMacVerifies() {
        EapAka.Keys keys = EapAka.deriveKeys(IDENTITY, IK, CK);
        byte[] challenge = EapAka.challenge(7, RAND, AUTN, keys.kAut());
        assertThat(HEX.formatHex(challenge)).isEqualTo(
                "01070044170100000105000023553cbe9637a89d218ae64dae47bf35"
                + "0205000055f328b43577000d0dd7d6b9b607f8cb"
                + "0b0500004beddb81ce2fb056dfbdb4bb61224338");
        EapAka.Packet p = EapAka.parse(challenge);
        assertThat(p.code()).isEqualTo(EapAka.CODE_REQUEST);
        assertThat(p.identifier()).isEqualTo(7);
        assertThat(p.subtype()).isEqualTo(EapAka.SUBTYPE_CHALLENGE);
        assertThat(EapAka.verifyMac(p, keys.kAut())).isTrue();
        assertThat(EapAka.verifyMac(p, new byte[16])).isFalse();
    }

    @Test
    void resIsReadFromTheResponse() {
        // an AT_RES attribute: 64 bits, then the 8-byte RES (as a USIM answers)
        byte[] res = HEX.parseHex("a54211d5e3ba50bf");
        byte[] attr = new byte[] { 3, 3, 0, 64, res[0], res[1], res[2], res[3], res[4], res[5], res[6], res[7] };
        byte[] pkt = new byte[8 + attr.length];
        pkt[0] = 2; pkt[1] = 7; pkt[2] = 0; pkt[3] = (byte) pkt.length; pkt[4] = 23; pkt[5] = 1;
        System.arraycopy(attr, 0, pkt, 8, attr.length);
        assertThat(HEX.formatHex(EapAka.res(EapAka.parse(pkt)))).isEqualTo("a54211d5e3ba50bf");
    }
}
