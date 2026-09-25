package com.bss.som.seam;

/**
 * What a seam adapter did. {@code realised} false = a business "nothing to
 * do" (no pool here, no owner serves the address): no realisation is recorded,
 * exactly as before the executor existed.
 *
 * @param realised       whether the seam was exercised
 * @param vendor         who served it (the adapter's own name for itself, or the tenant's configured vendor)
 * @param externalRef    the vendor's reference (a number, an ICCID, an access order id, a code)
 * @param clearsDeferral true when the seam stood the line up so the item need not wait for an install
 * @param note           why nothing was done, for the log
 */
public record SeamResult(boolean realised, String vendor, String externalRef, boolean clearsDeferral, String note) {

    public static SeamResult realised(String vendor, String externalRef) {
        return new SeamResult(true, vendor, externalRef, false, null);
    }

    public static SeamResult realisedAndUp(String vendor, String externalRef) {
        return new SeamResult(true, vendor, externalRef, true, null);
    }

    public static SeamResult skipped(String note) {
        return new SeamResult(false, null, null, false, note);
    }
}
