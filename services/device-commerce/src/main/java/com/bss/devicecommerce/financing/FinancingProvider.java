package com.bss.devicecommerce.financing;

import com.bss.devicecommerce.entity.DeviceAgreement;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The financing seam (PSP doctrine): three models exist in the wild and a
 * tenant may run any of them — the operator carrying the receivable, a
 * partner bank owning title and receivable, or a BNPL provider paid out at
 * checkout. One port, one driver per model; credit decisioning belongs to
 * the financier in models 2–3, never to this interface.
 */
public interface FinancingProvider {

    /** Which financing model this driver serves (DeviceAgreement constant). */
    String model();

    /** Price a proposed financing: monthly amount, total cost, model notes. */
    Map<String, Object> quote(Map<String, Object> terms);

    /**
     * Originate at agreement creation. May set financier refs, title holder
     * and status on the agreement; throws when the model's preconditions are
     * not met (e.g. BNPL without a paid checkout payment).
     */
    void originate(DeviceAgreement agreement, Map<String, Object> dto);

    /** The financier's payout landed (webhook face; mock drivers call it in-process). */
    void payoutReceived(DeviceAgreement agreement);

    /** What ending the financing early costs right now. */
    Map<String, Object> earlySettlementQuote(DeviceAgreement agreement);

    /**
     * Settle the remainder (upgrade/swap path). tradeInValue is what the
     * graded old device contributes. Returns the model-specific settlement
     * facts for the saga's response and events.
     */
    Map<String, Object> settle(DeviceAgreement agreement, BigDecimal tradeInValue);
}
