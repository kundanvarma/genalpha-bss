package com.bss.devicecommerce.financing;

import com.bss.devicecommerce.dto.DeviceAgreementRequest;
import com.bss.devicecommerce.dto.EarlySettlementQuote;
import com.bss.devicecommerce.dto.FinancingQuote;
import com.bss.devicecommerce.dto.FinancingSettlement;
import com.bss.devicecommerce.dto.FinancingTerms;
import com.bss.devicecommerce.entity.DeviceAgreement;

import java.math.BigDecimal;

/**
 * The financing seam (PSP doctrine): three models exist in the wild and a
 * tenant may run any of them — the operator carrying the receivable, a
 * partner bank owning title and receivable, or a BNPL provider paid out at
 * checkout. One port, one driver per model; credit decisioning belongs to
 * the financier in models 2–3, never to this interface. Every driver
 * answers in the same house records — a consumer never sees a vendor shape.
 */
public interface FinancingProvider {

    /** Which financing model this driver serves (DeviceAgreement constant). */
    String model();

    /** Price a proposed financing: monthly amount, total cost, model notes. */
    FinancingQuote quote(FinancingTerms terms);

    /**
     * Originate at agreement creation. May set financier refs, title holder
     * and status on the agreement; throws when the model's preconditions are
     * not met (e.g. BNPL without a paid checkout payment).
     */
    void originate(DeviceAgreement agreement, DeviceAgreementRequest request);

    /** The financier's payout landed (webhook face; mock drivers call it in-process). */
    void payoutReceived(DeviceAgreement agreement);

    /** What ending the financing early costs right now. */
    EarlySettlementQuote earlySettlementQuote(DeviceAgreement agreement);

    /**
     * Settle the remainder (upgrade/swap path). tradeInValue is what the
     * graded old device contributes. Returns the model-specific settlement
     * facts for the saga's response and events.
     */
    FinancingSettlement settle(DeviceAgreement agreement, BigDecimal tradeInValue);
}
