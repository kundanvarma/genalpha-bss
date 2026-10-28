package com.bss.benefit.controller;

import com.bss.benefit.api.ApiConstants;
import com.bss.benefit.dto.ChoiceRequests;
import com.bss.benefit.dto.PartnerCostView;
import com.bss.benefit.dto.PoolView;
import com.bss.benefit.exception.BadRequestException;
import com.bss.benefit.security.PartyScope;
import com.bss.benefit.service.ChoiceLedger;
import com.bss.benefit.service.PartnerCosts;
import com.bss.benefit.service.PartnerInvoices;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The household's Choices. A customer reads their own pool (the party comes
 * from the token, never a parameter); staff name the customer. Holding,
 * settling and releasing are ordering's alone — SecurityConfig closes them to
 * every token without "benefit:write", which no person carries.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class ChoiceController {

    private final ChoiceLedger ledger;
    private final PartyScope partyScope;
    private final PartnerCosts partnerCosts;
    private final PartnerInvoices partnerInvoices;

    public ChoiceController(ChoiceLedger ledger, PartyScope partyScope, PartnerCosts partnerCosts,
            PartnerInvoices partnerInvoices) {
        this.ledger = ledger;
        this.partyScope = partyScope;
        this.partnerCosts = partnerCosts;
        this.partnerInvoices = partnerInvoices;
    }

    /** Finance records a partner's invoice for a month (#390); the same invoice twice is one. */
    @PostMapping("/partnerInvoice")
    @ResponseStatus(HttpStatus.CREATED)
    public PartnerCostView.Invoice recordInvoice(@Valid @RequestBody ChoiceRequests.PartnerInvoice request) {
        return partnerInvoices.record(request);
    }

    /** Finance marks an invoice paid — once; revenue books the settlement of the payable. */
    @PostMapping("/partnerInvoice/{id}/pay")
    public PartnerCostView.Invoice payInvoice(@PathVariable String id) {
        return partnerInvoices.pay(id);
    }

    /** What the operator owes its partners for a month (YYYY-MM, default this one) — finance only. */
    @GetMapping("/partnerCost")
    public PartnerCostView partnerCost(@RequestParam(required = false) String period) {
        String month = period == null || period.isBlank() ? java.time.YearMonth.now(java.time.ZoneOffset.UTC).toString()
                : period;
        if (!month.matches("\\d{4}-(0[1-9]|1[0-2])")) {
            throw new BadRequestException("the period is a month, like 2026-10");
        }
        return partnerCosts.month(month);
    }

    @GetMapping("/pool")
    public PoolView pool(@RequestParam(required = false) String ownerPartyId) {
        String owner = partyScope.scopedPartyId().orElse(ownerPartyId);
        if (owner == null || owner.isBlank()) {
            throw new BadRequestException("name the customer (ownerPartyId)");
        }
        return ledger.pool(owner);
    }

    @PostMapping("/hold")
    @ResponseStatus(HttpStatus.CREATED)
    public PoolView.Allocation hold(@Valid @RequestBody ChoiceRequests.Hold request) {
        return ledger.hold(request);
    }

    @PostMapping("/order/{orderId}/settle")
    public PoolView settle(@PathVariable String orderId, @Valid @RequestBody ChoiceRequests.Settle request) {
        return ledger.settle(orderId, request);
    }

    @PostMapping("/goodwill")
    @ResponseStatus(HttpStatus.CREATED)
    public PoolView goodwill(@Valid @RequestBody ChoiceRequests.Goodwill request) {
        return ledger.goodwill(request);
    }

    @PostMapping("/order/{orderId}/release")
    public ReleaseReceipt release(@PathVariable String orderId) {
        return new ReleaseReceipt(orderId, ledger.release(orderId));
    }

    /** How many held Choices an order gave back. */
    public record ReleaseReceipt(String orderId, int released) {
    }
}
