package com.bss.ordering.credit;

/**
 * Why a credit decision came out the way it did (#422) — a short machine code
 * and the plain-language sentence a customer is shown. Derived ONLY from what
 * the bureau port actually handed the service (decision, score band, whether a
 * payment remark exists), so the explanation can never claim more than the
 * service knew. The threshold is the bureau's answer for this purpose, not a
 * number the service keeps; the sentence says so in plain words.
 *
 * <p>Codes are stable wire values: the shop translates by code and falls back
 * to the sentence for a code it does not know.
 */
public record CreditReason(String code, String sentence) {

    public static final String SCORE_MEETS_THRESHOLD = "SCORE_MEETS_THRESHOLD";
    public static final String SCORE_NEAR_THRESHOLD = "SCORE_NEAR_THRESHOLD";
    public static final String SCORE_BELOW_THRESHOLD = "SCORE_BELOW_THRESHOLD";
    public static final String PAYMENT_REMARK = "PAYMENT_REMARK";
    public static final String CREDIT_FROZEN = "CREDIT_FROZEN";
    public static final String UNRECOGNISED_ANSWER = "UNRECOGNISED_ANSWER";

    private static final String REMARK = "a registered payment remark (an unpaid debt on record)";

    /** The reason for a bureau answer, in the order the service weighs it. */
    public static CreditReason of(CreditDecisionPort.Decision d) {
        String band = d.scoreBand();
        String decision = d.decision() == null ? "" : d.decision();
        return switch (decision) {
            case CreditDecisionPort.FROZEN -> new CreditReason(CREDIT_FROZEN,
                    "Your credit information is frozen at the credit bureau, so it could not be assessed.");
            case CreditDecisionPort.DECLINE -> d.remarksPresent()
                    ? new CreditReason(PAYMENT_REMARK, "The credit bureau reported " + REMARK
                            + (band == null ? "." : ", and placed you in score band " + band
                                    + ", below what this plan needs."))
                    : new CreditReason(SCORE_BELOW_THRESHOLD, band == null
                            ? "The credit bureau's assessment was below what this plan needs."
                            : "The credit bureau placed you in score band " + band + ", below what this plan needs.");
            case CreditDecisionPort.REVIEW -> new CreditReason(SCORE_NEAR_THRESHOLD,
                    (band == null ? "The credit bureau's assessment was close to what this plan needs"
                            : "The credit bureau placed you in score band " + band + ", close to what this plan needs")
                            + (d.remarksPresent() ? ", and reported " + REMARK + "." : "."));
            case CreditDecisionPort.APPROVE -> new CreditReason(SCORE_MEETS_THRESHOLD, band == null
                    ? "The credit bureau's assessment met what this plan needs."
                    : "The credit bureau placed you in score band " + band + ", which meets what this plan needs.");
            default -> new CreditReason(UNRECOGNISED_ANSWER,
                    "The credit bureau gave an answer this shop does not recognise.");
        };
    }
}
