package com.bss.ordering.credit;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * The automated-decision notice shown at checkout BEFORE a credit-checked
 * order is placed (#422): whether this shop checks credit at all (the
 * channel never decides that), with whom, for what, using which data, and
 * the customer's rights — a decline is never final without a way to a
 * person, and they can ask why and ask a person to look again.
 */
@JsonPropertyOrder({"checked", "bureau", "purpose", "dataUsed", "notice"})
public record CreditCheckNotice(
        boolean checked,
        String bureau,
        String purpose,
        List<CreditExplanation.DataCategory> dataUsed,
        String notice) {
}
