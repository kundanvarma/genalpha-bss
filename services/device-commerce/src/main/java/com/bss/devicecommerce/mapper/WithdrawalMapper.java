package com.bss.devicecommerce.mapper;

import com.bss.devicecommerce.dto.RelatedPartyRef;
import com.bss.devicecommerce.dto.WithdrawalCaseView;
import com.bss.devicecommerce.entity.WithdrawalCase;

import java.util.List;

/** Entity → the withdrawal-case wire view. */
public final class WithdrawalMapper {

    private WithdrawalMapper() {
    }

    public static WithdrawalCaseView view(WithdrawalCase w) {
        return new WithdrawalCaseView(
                w.getId(),
                w.getHref(),
                w.getAgreementRef(),
                w.getOrderRef(),
                w.getStatus(),
                w.getClockStart().toString(),
                w.getReturnGrade(),
                w.getDeduction(),
                w.getRefundAmount(),
                w.getRefundRef(),
                w.getPartyId() == null ? null : List.of(RelatedPartyRef.customer(w.getPartyId())),
                WithdrawalCaseView.TYPE);
    }
}
