package com.bss.porting.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development clearinghouse: applies the country's number-format and timeline
 * rules but talks to no external body. A number ending in 0000 is rejected
 * (invalid/quarantined at the donor) so the reject path is exercisable.
 */
@Component
@ConditionalOnProperty(name = "bss.porting.gateway", havingValue = "mock", matchIfMissing = true)
public class MockPortingGateway implements PortingGateway {

    @Override
    public Decision validate(PortingRequest request) {
        if (!PortingRules.numberValid(request.country(), request.phoneNumber())) {
            return new Decision(false, null,
                    "number does not match " + request.country() + " format");
        }
        if (request.phoneNumber().endsWith("0000")) {
            return new Decision(false, null, "losing operator rejected the port (number in dispute)");
        }
        return new Decision(true, PortingRules.cutoverFor(request.country()).toString(), null);
    }

    @Override
    public boolean confirmCutover(PortingRequest request) {
        return true;
    }

    @Override
    public String name() {
        return "mock";
    }
}
