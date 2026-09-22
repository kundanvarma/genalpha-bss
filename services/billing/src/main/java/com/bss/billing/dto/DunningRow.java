package com.bss.billing.dto;

/**
 * A row of the dunning window: an installment straggler (DunningCase) or an
 * account's collection case (CollectionCase). Finitely many house meanings.
 */
public sealed interface DunningRow permits DunningCaseView, CollectionCaseView {
}
