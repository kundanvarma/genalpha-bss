package com.bss.entitlement.service;

import com.bss.entitlement.client.AucClient;
import com.bss.entitlement.eap.EapAka;
import com.bss.entitlement.entity.EapSession;
import com.bss.entitlement.repository.EapSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * TS.43 §2.8.1 — EAP-AKA over HTTP ("EAP relay"). The phone says who it is
 * (EAP_ID, the root NAI carrying the IMSI); we fetch a vector from the
 * tenant's AUC, answer with an EAP-Request/AKA-Challenge in an
 * {@code application/vnd.gsma.eap-relay.v1.0+json} body and a session cookie;
 * the USIM computes RES and the client posts EAP-Response/AKA-Challenge back;
 * we check RES against XRES and AT_MAC against K_aut. The pending challenge
 * lives in the database, so any instance can finish what another started.
 */
@Service
public class EapAkaService {

    private static final Logger log = LoggerFactory.getLogger(EapAkaService.class);
    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What a completed relay hands back. */
    public record Outcome(boolean ok, String imsi, String reason) { }

    private final AucClient auc;
    private final EapSessionRepository sessions;
    private final long sessionSeconds;

    public EapAkaService(AucClient auc, EapSessionRepository sessions,
            @Value("${bss.entitlement.eap-session-seconds:120}") long sessionSeconds) {
        this.auc = auc;
        this.sessions = sessions;
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
    @Transactional
    public Optional<Map<String, String>> start(String tenantId, String eapId) {
        Optional<String> imsi = imsiOf(eapId);
        if (imsi.isEmpty()) {
            return Optional.empty();
        }
        Optional<AucClient.Vector> vector = auc.vector(tenantId, imsi.get());
        if (vector.isEmpty()) {
            return Optional.empty();
        }
        AucClient.Vector v = vector.get();
        EapAka.Keys keys = EapAka.deriveKeys(eapId, HEX.parseHex(v.ik()), HEX.parseHex(v.ck()));
        int identifier = RANDOM.nextInt(256);
        byte[] challenge = EapAka.challenge(identifier, HEX.parseHex(v.rand()), HEX.parseHex(v.autn()), keys.kAut());
        byte[] idBytes = new byte[24];
        RANDOM.nextBytes(idBytes);
        EapSession s = new EapSession();
        s.setId(Base64.getUrlEncoder().withoutPadding().encodeToString(idBytes));
        s.setTenantId(tenantId);
        s.setEapId(eapId);
        s.setImsi(imsi.get());
        s.setIdentifier(identifier);
        s.setXresHex(v.xres());
        s.setKautHex(HEX.formatHex(keys.kAut()));
        s.setCreatedAt(OffsetDateTime.now());
        sessions.save(s);
        return Optional.of(Map.of("session", s.getId(), "packet", Base64.getEncoder().encodeToString(challenge)));
    }

    /** Finish a relay with the device's EAP-Response/AKA-Challenge (base64). One shot: the session is consumed. */
    @Transactional
    public Outcome complete(String tenantId, String sessionId, String packetBase64) {
        EapSession s = sessionId == null ? null : sessions.findByIdAndTenantId(sessionId, tenantId).orElse(null);
        if (s == null) {
            return new Outcome(false, null, "no such EAP session");
        }
        sessions.delete(s);
        if (s.getCreatedAt().plusSeconds(sessionSeconds).isBefore(OffsetDateTime.now())) {
            return new Outcome(false, s.getImsi(), "EAP session expired");
        }
        EapAka.Packet p;
        try {
            p = EapAka.parse(Base64.getDecoder().decode(packetBase64));
        } catch (RuntimeException e) {
            return new Outcome(false, s.getImsi(), "unreadable EAP packet");
        }
        if (p.code() != EapAka.CODE_RESPONSE || p.type() != EapAka.TYPE_AKA || p.subtype() != EapAka.SUBTYPE_CHALLENGE) {
            return new Outcome(false, s.getImsi(), "not an EAP-Response/AKA-Challenge");
        }
        if (p.identifier() != s.getIdentifier()) {
            return new Outcome(false, s.getImsi(), "EAP identifier mismatch");
        }
        byte[] kAut = HEX.parseHex(s.getKautHex());
        if (!EapAka.verifyMac(p, kAut)) {
            return new Outcome(false, s.getImsi(), "AT_MAC invalid");
        }
        byte[] res = EapAka.res(p);
        if (res == null || !MessageDigest.isEqual(res, HEX.parseHex(s.getXresHex()))) {
            log.info("EAP-AKA: RES mismatch for IMSI {}", s.getImsi());
            return new Outcome(false, s.getImsi(), "RES does not match XRES");
        }
        return new Outcome(true, s.getImsi(), "ok");
    }

    @Scheduled(fixedDelayString = "${bss.entitlement.eap-sweep-ms:60000}")
    @Transactional
    public void sweep() {
        try (com.bss.entitlement.security.TenantContext ignored = com.bss.entitlement.security.TenantContext.actAsSystem()) {
            sessions.deleteByCreatedAtBefore(OffsetDateTime.now().minusSeconds(sessionSeconds * 2));
        }
    }
}
