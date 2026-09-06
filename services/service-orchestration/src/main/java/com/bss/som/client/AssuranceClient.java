package com.bss.som.client;

/**
 * The assurance seam from the production layer: when a SOLD guarantee is not
 * met, the SOM declares a TMF656 service problem on the line — the assurance
 * component owns what follows (the SLA ledger credits it). Fail-open: a
 * missing assurance component means no guarantee accounting, never a stuck sweep.
 */
public interface AssuranceClient {

    void reportSliceShortfall(String tenantId, String serviceId, String partyId,
            int guaranteedDlMbps, double measuredDlMbps, Integer windowMinutes);
}
