package com.bss.ordering.controller;

import com.bss.ordering.api.ApiConstants;
import com.bss.ordering.exception.OrderValidationException;
import com.bss.ordering.security.PartyScope;
import com.bss.ordering.service.ChoicePreview;
import com.bss.ordering.service.ProductOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Can I pick this?" before ordering (#392). A house endpoint beside TMF622 —
 * the answer an order would give, holding nothing. A customer asks for its own
 * household (the party is the token); staff name the household; a business
 * admin may name only a member of its own organization, as an order would.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/choicePreview")
public class ChoicePreviewController {

    private final ChoicePreview preview;
    private final PartyScope partyScope;
    private final ProductOrderService orders;

    public ChoicePreviewController(ChoicePreview preview, PartyScope partyScope, ProductOrderService orders) {
        this.preview = preview;
        this.partyScope = partyScope;
        this.orders = orders;
    }

    @GetMapping
    public ChoicePreview.Answer preview(@RequestParam String offeringId,
            @RequestParam(required = false) String partyId,
            @RequestParam(required = false) String lineId) {
        String owner = partyScope.scopedPartyId().orElse(partyId);
        if (owner == null || owner.isBlank()) {
            throw new OrderValidationException("name the household (partyId)");
        }
        // the same organization wall order creation applies — never trust the named party
        orders.requireSameOrgForBusinessAdmin(owner);
        return preview.preview(owner, offeringId, lineId);
    }
}
