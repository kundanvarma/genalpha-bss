package com.bss.entitlement.client;

import java.util.Optional;

/**
 * The AUC seam: who answers "give me an authentication vector for this IMSI"?
 * The operator's HSS/UDM — or, for an MVNO, the host MNO's — behind an
 * adapter (SWx/S6a/Nudm in production, the REST shape of mock-hss in dev).
 * The ECS never sees the subscriber key K; it gets RAND/AUTN to challenge
 * with and XRES/CK/IK to check and derive from.
 */
public interface AucClient {

    /** One Milenage vector for the IMSI (hex strings), empty when the AUC does not know the subscriber. */
    Optional<Vector> vector(String imsi);

    /** The AUC's identity record for the IMSI, when it keeps one. */
    Optional<Identity> identity(String imsi);

    record Vector(String rand, String autn, String xres, String ck, String ik) { }

    record Identity(String imsi, String iccid, String msisdn) { }
}
