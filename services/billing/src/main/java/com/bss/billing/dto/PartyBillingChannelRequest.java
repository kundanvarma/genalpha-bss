package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A CSR records what the customer chose: efaktura, mailbox or print; consented=false withdraws. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PartyBillingChannelRequest(String partyId, String channel, Boolean consented, String aliasRef) {
}
