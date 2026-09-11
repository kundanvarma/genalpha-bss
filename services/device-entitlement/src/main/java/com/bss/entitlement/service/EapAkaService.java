package com.bss.entitlement.service;

import com.bss.entitlement.client.AucClient;
import com.bss.entitlement.eap.EapAka;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TS.43 §2.8.1 — EAP-AKA over HTTP ("EAP relay"). The phone says who it is
 * (EAP_ID, the root NAI carrying the IMSI); we fetch a vector from the AUC
 * seam, answer with an EAP-Request/AKA-Challenge in an
 * {@code application/vnd.gsma.eap-relay.v1.0+json} body and a session cookie;
 * the USIM computes RES and the client posts EAP-Response/AKA-Challenge back;
 * we check RES against XRES and AT_MAC against K_aut. One round, one session,
 * short-lived and in memory (one instance serves a demo; a cluster would keep
 * the session in the database or a cache).
 */
@Service
public class EapAkaService {

    private static final Logger log = LoggerFactory.getLogger(EapAkaService.class);
    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The pending challenge for one device. */
    public record Session(String id, String tenantId, String eapId, String imsi, int identifier,
            byte[] xres, EapAka.Keys keys, long createdAt) { }

    /** What a completed relay hands back. */
    public record Outcome(boolean ok, String imsi, String reason) { }

    private final AucClient auc;
    private final long sessionSeconds;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public EapAkaService(AucClient auc, @Value("${bss.entitlement.eap-session-seconds:120}") long sessionSeconds) {
        this.auc = auc;
        this.sessionSeconds = sessionSeconds;
    }

    /** The IMSI inside a root NAI: {@code 0<IMSI>@nai.epc.mnc<MNC>.mcc<MCC>.3gppnetwork.org}. */
    public static Optional<String> imsiOf(String eapId) {
        if (eapId == null) {
            return Optional.empty();
        }
        String user = eapId.contains("@") ? eapId.substring(0, eapId.indexOf('@')) : eapId;
        if (user.length() >= 7 && user.charAt(0) == '0' && user.substring(1).chars().allMatch(Character::isDigit)) {
            return Optional.of(user.substring(1));
        }
        if (user.chars().allMatch(Character::isDigit) && user.length() >= 6) {
            return Optional.of(user); // a bare IMSI
        }
        return Optional.empty();
    }

    /** Start a relay: the challenge packet (base64) and the session id to cookie. Empty when the AUC does not know the IMSI. */
    public Optional<Map<String, String>> start(String tenantId, String eapId) {
        Optional<String> imsi = imsiOf(eapId);
        if (imsi.isEmpty()) {
            return Optional.empty();
        }
        Optional<AucClient.Vector> vector = auc.vector(imsi.get());
        if (vector.isEmpty()) {
            return Optional.empty();
        }
        AucClient.Vector v = vector.get();
        EapAka.Keys keys = EapAka.deriveKeys(eapId, HEX.parseHex(v.ik()), HEX.parseHex(v.ck()));
        int identifier = RANDOM.nextInt(256);
        byte[] challenge = EapAka.challenge(identifier, HEX.parseHex(v.rand()), HEX.parseHex(v.autn()), keys.kAut());
        byte[] idBytes = new byte[24];
        RANDOM.nextBytes(idBytes);
        String sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(idBytes);
        sessions.put(sessionId, new Session(sessionId, tenantId, eapId, imsi.get(), identifier,
                HEX.parseHex(v.xres()), keys, System.currentTimeMillis()));
        sweep();
        return Optional.of(Map.of("session", sessionId, "packet", Base64.getEncoder().encodeToString(challenge)));
    }

    /** Finish a relay with the device's EAP-Response/AKA-Challenge (base64). */
    public Outcome complete(String tenantId, String sessionId, String packetBase64) {
        Session s = sessionId == null ? null : sessions.remove(sessionId);
        if (s == null || !s.tenantId().equals(tenantId)) {
            return new Outcome(false, null, "no such EAP session");
        }
        if (System.currentTimeMillis() - s.createdAt() > sessionSeconds * 1000) {
            return new Outcome(false, s.imsi(), "EAP session expired");
        }
        EapAka.Packet p;
        try {
            p = EapAka.parse(Base64.getDecoder().decode(packetBase64));
        } catch (RuntimeException e) {
            return new Outcome(false, s.imsi(), "unreadable EAP packet");
        }
        if (p.code() != EapAka.CODE_RESPONSE || p.type() != EapAka.TYPE_AKA || p.subtype() != EapAka.SUBTYPE_CHALLENGE) {
            return new Outcome(false, s.imsi(), "not an EAP-Response/AKA-Challenge");
        }
        if (p.identifier() != s.identifier()) {
            return new Outcome(false, s.imsi(), "EAP identifier mismatch");
        }
        if (!EapAka.verifyMac(p, s.keys().kAut())) {
            return new Outcome(false, s.imsi(), "AT_MAC invalid");
        }
        byte[] res = EapAka.res(p);
        if (res == null || !MessageDigest.isEqual(res, s.xres())) {
            log.info("EAP-AKA: RES mismatch for IMSI {}", s.imsi());
            return new Outcome(false, s.imsi(), "RES does not match XRES");
        }
        return new Outcome(true, s.imsi(), "ok");
    }

    private void sweep() {
        long cutoff = System.currentTimeMillis() - sessionSeconds * 1000;
        sessions.values().removeIf(s -> s.createdAt() < cutoff);
    }
}
