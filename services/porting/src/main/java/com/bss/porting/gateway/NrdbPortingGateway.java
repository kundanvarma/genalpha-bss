package com.bss.porting.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Norway: porting is coordinated through NRDB (Nasjonal Referansedatabase),
 * the central reference database every Norwegian operator uses to exchange
 * porting messages under Nkom's rules. This adapter is shaped like that flow
 * — validate the +47 number and the donor, agree a next-day cutover — but
 * does not connect to the real NRDB (which needs operator accreditation);
 * it's the seam a production deployment implements against NRDB's interface,
 * exactly like the PSP and BankID seams elsewhere in the BSS.
 */
@Component
@ConditionalOnProperty(name = "bss.porting.gateway", havingValue = "nrdb")
public class NrdbPortingGateway implements PortingGateway {

    @Override
    public Decision validate(PortingRequest request) {
        // NRDB only handles Norwegian numbering; anything else is out of scope.
        if (!"NO".equalsIgnoreCase(request.country())
                || !PortingRules.numberValid("NO", request.phoneNumber())) {
            return new Decision(false, null, "NRDB handles Norwegian (+47) numbers only");
        }
        if (request.otherOperator() == null || request.otherOperator().isBlank()) {
            return new Decision(false, null, "the losing operator (donor) is required for an NRDB port");
        }
        if (request.phoneNumber().endsWith("0000")) {
            return new Decision(false, null, "donor operator rejected the port via NRDB");
        }
        // Nkom mandates fast porting; NRDB agrees a next-business-day cutover.
        return new Decision(true, PortingRules.cutoverFor("NO").toString(), null);
    }

    @Override
    public boolean confirmCutover(PortingRequest request) {
        return true;
    }

    @Override
    public String name() {
        return "nrdb";
    }
}
