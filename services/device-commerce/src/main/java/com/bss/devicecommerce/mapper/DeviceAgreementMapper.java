package com.bss.devicecommerce.mapper;

import com.bss.devicecommerce.dto.DeviceAgreementView;
import com.bss.devicecommerce.dto.DeviceRef;
import com.bss.devicecommerce.dto.RelatedPartyRef;
import com.bss.devicecommerce.dto.UpgradeRule;
import com.bss.devicecommerce.entity.DeviceAgreement;
import com.bss.devicecommerce.financing.FinancingMath;

import java.util.List;

/** Entity → the wire view every channel, billing and revenue read. */
public final class DeviceAgreementMapper {

    private DeviceAgreementMapper() {
    }

    public static DeviceAgreementView view(DeviceAgreement a) {
        return new DeviceAgreementView(
                a.getId(),
                a.getHref(),
                a.getFinancingModel(),
                a.getStatus(),
                a.getSubscriptionRef(),
                a.getOrderRef(),
                a.getDeviceRef() == null ? null
                        : DeviceRef.logicalResource(a.getDeviceRef(), a.getImei(), a.getSerialNo()),
                a.getPrincipal(),
                a.getTermMonths(),
                a.getMonthlyAmount(),
                a.getTotalCostOfOwnership(),
                a.getCurrency(),
                a.getInstallmentsPaid(),
                FinancingMath.paidSharePct(a),
                FinancingMath.remainingPrincipal(a),
                a.getFinancierRef(),
                a.getExternalAgreementNo(),
                a.getTitleHolder(),
                UpgradeRule.of(a.getUpgradeRuleType(), a.getUpgradeRuleValue()),
                a.getResidualValue(),
                a.getSubsidyAmount(),
                a.getShippingCost(),
                a.getPaymentRef(),
                a.getPayoutReceivedAt() == null ? null : a.getPayoutReceivedAt().toString(),
                a.getDeliveredAt() == null ? null : a.getDeliveredAt().toString(),
                a.getTradeInDelta(),
                List.of(RelatedPartyRef.customer(a.getPartyId())),
                DeviceAgreementView.TYPE);
    }
}
