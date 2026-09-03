package com.bss.porting.gateway;

import org.springframework.stereotype.Component;

/**
 * Guyana: number portability went live on 2025-02-10 under PUC rules, run
 * through the Porting XS clearinghouse (PXS B.V.) for all three operators.
 * The rules a port must satisfy: a +592 mobile (6xx/7xx) or fixed number,
 * the donor named, government photo ID recorded, no outstanding postpaid
 * invoices or prepaid loans, and the cutover within one business day. This
 * adapter is shaped like that flow — format, donor, dispute reject, next-day
 * cutover — and does not connect to the real clearinghouse (which needs an
 * operator account); it is the seam a Guyanese deployment implements against
 * Porting XS's interface.
 */
@Component
public class PortingXsGateway implements PortingGateway {

    @Override
    public Decision validate(PortingRequest request) {
        if (!"GY".equalsIgnoreCase(request.country())
                || !PortingRules.numberValid("GY", request.phoneNumber())) {
            return new Decision(false, null, "Porting XS handles Guyanese (+592) numbers only");
        }
        if (request.otherOperator() == null || request.otherOperator().isBlank()) {
            return new Decision(false, null, "the losing operator (donor) is required for a port under PUC rules");
        }
        if (request.phoneNumber().endsWith("0000")) {
            return new Decision(false, null, "donor operator rejected the port (outstanding balance or suspended line)");
        }
        // PUC: mobile ports complete within one business day
        return new Decision(true, PortingRules.cutoverFor("GY").toString(), null);
    }

    @Override
    public boolean confirmCutover(PortingRequest request) {
        return true;
    }

    @Override
    public String name() {
        return "portingxs";
    }
}
