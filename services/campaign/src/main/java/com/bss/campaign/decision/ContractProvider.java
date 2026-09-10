package com.bss.campaign.decision;

import java.util.Optional;

/** Where the seam reads the tenant's Learning Contract for a point; empty = defaults. */
public interface ContractProvider {

    Optional<Contract> contractFor(String decisionPoint);
}
