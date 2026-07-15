package com.bss.campaign.client;

import java.math.BigDecimal;
import java.util.List;

/** What is this basket of offerings worth per month? The catalog knows. */
public interface CatalogClient {

    BigDecimal monthlyValueOf(String tenantId, List<String> offeringIds);
}
