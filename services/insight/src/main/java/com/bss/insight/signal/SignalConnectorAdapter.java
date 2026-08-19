package com.bss.insight.signal;

import com.bss.insight.entity.SignalConnector;

import java.util.List;
import java.util.Map;

/**
 * One kind of external signal source (SI-P2). Beans implementing this are
 * auto-discovered (the carrier idiom): a new desk/review/CRM source is one
 * class + a config row, never a new pipeline. Poll-mode adapters pull items;
 * webhook-mode connectors skip the adapter entirely — the generic hook maps
 * the pushed body by JSON pointers. Either way every item goes through the
 * SI-P1 firewall in SignalService; an adapter never stores anything itself.
 */
public interface SignalConnectorAdapter {

    /** The key used in signal_connector.kind ('servicedesk'). */
    String kind();

    /**
     * Pull the source's items, mapped to signal DTOs (text, sourceRef,
     * partyId?, lang?, context?). The service adds source + runs the firewall.
     */
    List<Map<String, Object>> pull(SignalConnector cfg);
}
