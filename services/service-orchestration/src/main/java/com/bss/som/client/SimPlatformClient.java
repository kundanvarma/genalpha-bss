package com.bss.som.client;

/**
 * The SIM-platform seam: PIN changes execute on the card via the operator's
 * SIM/OTA platform (HSS-side), not in the BSS. One implementation per
 * platform; dev ships a mock — the same pluggable pattern as the PSP and
 * the porting clearinghouse.
 */
public interface SimPlatformClient {

    /** Push a new PIN to the card. Returns false when the platform refuses. */
    boolean resetPin(String iccid, String newPin);

    /** Kill a card at the network (lost/stolen/replaced). False = refused. */
    boolean block(String iccid);
}
