package com.bss.entitlement.client;

import java.util.Optional;

/**
 * The AUC seam: who answers "give me an authentication vector for this IMSI"?
 * The operator's HSS/UDM — or, for an MVNO, the host MNO's — behind an
 * adapter (SWx/S6a/Nudm in production, the REST shape of mock-hss in dev),
 * resolved PER TENANT (tenants.yml {@code auc-base-url}). The ECS never sees
 * the subscriber key K; it gets RAND/AUTN to challenge with and XRES/CK/IK
 * to check and derive from. The same authority knows which IMSI a SIM
 * (ICCID) carries.
 */
public interface AucClient {

    /** One Milenage vector for the IMSI (hex strings), empty when the AUC does not know the subscriber. */
    Optional<Vector> vector(String tenantId, String imsi);

    /** The AUC's identity record for the IMSI, when it keeps one. */
    Optional<Identity> identity(String tenantId, String imsi);

    /** The identity behind a SIM: the IMSI a real HSS pairs with the ICCID.
     * A dev AUC may allocate one when the ICCID is new to it. */
    Optional<Identity> identityByIccid(String tenantId, String iccid, String msisdn);

    /** Whether this tenant has an AUC bound at all. */
    boolean enabled(String tenantId);

    record Vector(String rand, String autn, String xres, String ck, String ik) { }

    record Identity(String imsi, String iccid, String msisdn) { }
}
