package com.bss.som.client;

/**
 * The partner-fulfilment seam: a sold partner service (a streaming or an
 * audiobook service) activates on the PARTNER's platform, not our network.
 * One adapter per partner platform, chosen per tenant (tenants.yml
 * {@code partner-platform}, through {@link TenantPartnerRouter}); the fleet
 * ships a mock in-process and a REST stand-in. Settlement is a later ticket.
 *
 * <p>Activation is honest about time: a partner may confirm at once, later, or
 * refuse — and a timeout is NOT a failure, it is "we do not know yet". The
 * request key (the service id) is the partner's idempotency key, so asking
 * {@link #status} before retrying can never activate twice.
 *
 * <p>Both directions: an ended service stops with the partner too
 * ({@link #deactivate}), or a released benefit would keep running and costing.
 */
public interface PartnerEntitlementClient {

    /** The adapter's name as tenants.yml names it ({@code mock}, {@code http}). */
    String name();

    /** Who provides this seam for the tenant: the adapter in use, named honestly. */
    default String vendor(String tenantId) {
        return getClass().getSimpleName();
    }

    /** Ask the partner to activate; the answer may be active, pending, refused — or unknown. */
    Activation activate(String tenantId, String requestKey, String offeringName, String customerPartyId);

    /** What the partner knows about this request key now (unknown when it never received it). */
    Activation status(String tenantId, String requestKey);

    /** Stop it with the partner. Throws when the partner could not be told — the caller keeps it to retry. */
    void deactivate(String tenantId, String requestKey, String activationCode, String customerPartyId);

    enum State { ACTIVE, PENDING, REFUSED, UNKNOWN }

    record Activation(State state, String code, String reason) {
        public static Activation active(String code) {
            return new Activation(State.ACTIVE, code, null);
        }

        public static Activation pending() {
            return new Activation(State.PENDING, null, null);
        }

        public static Activation refused(String reason) {
            return new Activation(State.REFUSED, null, reason);
        }

        public static Activation unknown(String reason) {
            return new Activation(State.UNKNOWN, null, reason);
        }
    }
}
