package com.bss.catalog.pim;

import com.bss.catalog.dto.ProductOfferingDto;

import java.util.List;
import java.util.Map;

/**
 * The catalog's content seam: where an offering's imagery comes from.
 *
 * Operators who manage product content here author it through TMF667 (the
 * document component) and the console; the stored attachment list is served
 * as-is. Operators with an existing PIM (Akeneo, inRiver, a homegrown DAM)
 * plug it in per tenant — the adapter resolves the offering to that system's
 * media and the channels never know the difference, because everything they
 * read stays TMF620: an attachment list of {name, mimeType, url}.
 */
public interface ProductContentSource {

    /**
     * The offering's attachments from this source, or null when the source
     * has nothing to say (not configured for the tenant, product unknown,
     * or the PIM is unreachable) — the catalog then keeps what it stores.
     * Never throws: content must not break commerce.
     */
    List<Map<String, Object>> attachmentsFor(String tenantId, ProductOfferingDto offering);
}
