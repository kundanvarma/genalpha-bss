package com.bss.som.controller;

import com.bss.som.api.ApiConstants;
import com.bss.som.client.WholesaleRateCardClient;
import com.bss.som.dto.WholesaleDtos.NotificationReceipt;
import com.bss.som.dto.WholesaleDtos.OwnerStatement;
import com.bss.som.dto.WholesaleDtos.SonataNotification;
import com.bss.som.dto.WholesaleDtos.WholesaleAccessOrderView;
import com.bss.som.dto.WholesaleDtos.WholesaleSettlement;
import com.bss.som.entity.WholesaleAccessOrder;
import com.bss.som.repository.WholesaleAccessOrderRepository;
import com.bss.som.security.TenantContext;
import com.bss.som.security.TenantScope;
import com.bss.som.security.WholesaleDoorAuth;
import com.bss.som.service.OrchestrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The wholesale/open-access face: the access-seeker orders we placed upstream to
 * fibre owners, so a back office (and wholesale billing) can see what we bought and
 * from whom. Read-only; service:read like the rest of the service-order face.
 */
@RestController
public class WholesaleAccessController {

    private static final String RETAIL_FIBRE = "GenAlpha Fiber 1000";

    private final WholesaleAccessOrderRepository wholesaleOrders;
    private final TenantScope tenantScope;
    private final WholesaleRateCardClient rateCard;
    private final OrchestrationService orchestration;
    private final WholesaleDoorAuth auth;

    public WholesaleAccessController(WholesaleAccessOrderRepository wholesaleOrders,
            TenantScope tenantScope, WholesaleRateCardClient rateCard, OrchestrationService orchestration,
            WholesaleDoorAuth auth) {
        this.wholesaleOrders = wholesaleOrders;
        this.tenantScope = tenantScope;
        this.rateCard = rateCard;
        this.orchestration = orchestration;
        this.auth = auth;
    }

    /**
     * The owner OSS's activation callback (MEF Sonata notification): the wholesale
     * access line is live. Anonymous — the owner is an external system, not a fleet
     * identity — but no longer unauthenticated: the order id alone used to be the
     * whole credential, and a bare POST to a guessed or leaked UUID completed the
     * retail order and booked wholesale COGS. The callback URL we hand the owner
     * now ends in a token bound to that order and derived from the ordering
     * tenant's wholesale secret; the owner posts to the URL it was given, exactly
     * as before. Idempotent.
     */
    @PostMapping(ApiConstants.ORDER_BASE + "/wholesaleAccessOrder/{id}/notification/{token}")
    public ResponseEntity<NotificationReceipt> notify(@PathVariable("id") String id,
            @PathVariable("token") String token,
            @RequestBody(required = false) SonataNotification body) {
        // the order's own tenant issued the callback, so read it out of band first
        String tenantId;
        try (TenantContext ignored = TenantContext.actAsSystem()) {
            tenantId = wholesaleOrders.findById(id).map(WholesaleAccessOrder::getTenantId).orElse(null);
        }
        if (tenantId == null) {
            // an order nobody placed and a bad token are the same answer: the door
            // must not become an oracle for which UUIDs exist
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "wholesale door not authorized");
        }
        auth.requireCallbackToken(tenantId, id, token);
        String sonataOrderId = body == null ? null : body.orderId();
        boolean activated = orchestration.activateWholesaleAccess(id, sonataOrderId);
        return ResponseEntity.ok(new NotificationReceipt(id, activated));
    }

    /**
     * The wholesale settlement: what we OWE each fibre owner for the access lines
     * live on their network (the accounts-payable side open access adds), and the
     * margin it leaves — retail monthly minus the wholesale rate. This is the money
     * the retail business runs on when it sells over someone else's fibre.
     */
    @GetMapping(ApiConstants.ORDER_BASE + "/wholesaleSettlement")
    public ResponseEntity<WholesaleSettlement> settlement() {
        String tenant = tenantScope.currentTenantId();
        Map<String, WholesaleRateCardClient.Rate> card = rateCard.rateCard();
        Double retailMonthly = rateCard.retailMonthly(RETAIL_FIBRE);

        // count live access lines per owner
        Map<String, Integer> linesByOwner = new TreeMap<>();
        for (WholesaleAccessOrder w : wholesaleOrders.findByTenantIdAndState(tenant, WholesaleAccessOrder.ACTIVE)) {
            linesByOwner.merge(w.getAccessOwner(), 1, Integer::sum);
        }

        List<OwnerStatement> statements = new ArrayList<>();
        double totalOwed = 0.0;
        double totalMargin = 0.0;
        int totalLines = 0;
        for (Map.Entry<String, Integer> e : linesByOwner.entrySet()) {
            String owner = e.getKey();
            int lines = e.getValue();
            WholesaleRateCardClient.Rate rate = card.get(owner);
            double perLine = rate == null ? 0.0 : rate.perLine();
            double owed = round(perLine * lines);
            Double retailPerLine = null;
            Double marginPerLine = null;
            if (retailMonthly != null && rate != null) {
                retailPerLine = retailMonthly;
                marginPerLine = round(retailMonthly - perLine);
                totalMargin += round((retailMonthly - perLine) * lines);
            }
            statements.add(new OwnerStatement(owner, rate == null ? null : rate.layer(), lines, perLine, owed,
                    retailPerLine, marginPerLine, "EUR"));
            totalOwed += owed;
            totalLines += lines;
        }
        return ResponseEntity.ok(new WholesaleSettlement("WholesaleSettlement", "month", statements, totalLines,
                round(totalOwed), round(totalMargin), "EUR"));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @GetMapping(ApiConstants.ORDER_BASE + "/wholesaleAccessOrder")
    public ResponseEntity<List<WholesaleAccessOrderView>> list(
            @RequestParam(required = false) String productOrderId,
            @RequestParam(required = false) String state) {
        String tenant = tenantScope.currentTenantId();
        List<WholesaleAccessOrder> rows = productOrderId != null
                ? wholesaleOrders.findByTenantIdAndProductOrderId(tenant, productOrderId)
                : state != null
                        ? wholesaleOrders.findByTenantIdAndState(tenant, state)
                        : wholesaleOrders.findByTenantId(tenant);
        return ResponseEntity.ok(rows.stream().map(this::view).toList());
    }

    private WholesaleAccessOrderView view(WholesaleAccessOrder w) {
        return new WholesaleAccessOrderView(w.getId(), w.getProductOrderId(), w.getServiceId(), w.getAccessOwner(),
                w.getAccessLayer(), w.getBandwidthMbps(), w.getPostCode(), w.getState(), w.getExternalId(),
                w.getActivatedAt(), w.getCreatedAt(), "WholesaleAccessOrder");
    }
}
