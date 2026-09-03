package com.bss.porting.gateway;

/**
 * The clearinghouse seam. Number portability is coordinated between operators
 * through a national body whose rules differ by country — Norway runs it
 * through NRDB (Nasjonal Referansedatabase), the UK through a different
 * process, the US through the NPAC, and so on. Everything above this line is
 * the same BSS porting order; the implementation below is one country's
 * regime. Selected by configuration (and overridable per tenant), so a
 * Norwegian operator gets NRDB and a dev deployment gets the mock, with no
 * change to the porting service.
 */
public interface PortingGateway {

    /**
     * Ask the clearinghouse to validate a port request against the losing
     * operator and number rules, and (if valid) agree a cutover window.
     */
    Decision validate(PortingRequest request);

    /** Confirm the cutover actually happened at the agreed time. */
    boolean confirmCutover(PortingRequest request);

    String name();

    /** The adapter that handles {@code tenantId}'s port of a number from {@code country} — the router answers per tenant, then per country. */
    default String nameFor(String tenantId, String country) {
        return name();
    }

    record PortingRequest(String direction, String phoneNumber, String country,
            String otherOperator, String subscriberRef, String tenantId) {
        public PortingRequest(String direction, String phoneNumber, String country,
                String otherOperator, String subscriberRef) {
            this(direction, phoneNumber, country, otherOperator, subscriberRef, null);
        }
    }

    /**
     * @param scheduledCutoverIso when the port will complete (ISO-8601), per
     *        the country's timelines; null when rejected.
     */
    record Decision(boolean accepted, String scheduledCutoverIso, String rejectReason) {
    }
}
