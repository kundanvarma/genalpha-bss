package com.bss.stock.service;

import com.bss.stock.dto.StockOperationDto;
import com.bss.stock.entity.ProductStock;
import com.bss.stock.entity.StockReservation;
import com.bss.stock.events.DomainEventPublisher;
import com.bss.stock.exception.BadRequestException;
import com.bss.stock.exception.ConflictException;
import com.bss.stock.repository.ProductStockRepository;
import com.bss.stock.repository.StockReservationRepository;
import com.bss.stock.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The TMF687-style task operations tied to the order lifecycle: an order
 * placement reserves stock, completion consumes it (decrementing the shelf),
 * cancellation releases it. Offerings without a stock record are not
 * stock-managed — reserving them is a no-op, so services and subscriptions
 * flow through untouched.
 */
@Service
public class StockOperationService {

    public static final String RESERVED = "reserved";
    public static final String NOT_MANAGED = "notManaged";

    private final ProductStockRepository stocks;
    private final StockReservationRepository reservations;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;

    public StockOperationService(ProductStockRepository stocks, StockReservationRepository reservations,
            DomainEventPublisher events, TenantScope tenantScope) {
        this.stocks = stocks;
        this.reservations = reservations;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    @Transactional
    public StockOperationDto reserve(StockOperationDto request) {
        String offeringId = refId(request.getProductOffering(), "productOffering");
        String orderId = refId(request.getRelatedOrder(), "relatedOrder");
        int quantity = request.getQuantity() == null ? 1 : request.getQuantity();
        if (quantity < 1) {
            throw new BadRequestException("quantity must be at least 1");
        }

        String tenantId = tenantScope.currentTenantId();
        ProductStock stock = stocks.findForUpdateByProductOfferingId(offeringId, tenantId).orElse(null);
        if (stock == null) {
            request.setState(NOT_MANAGED);
            return request;
        }

        int reserved = reservations.activeQuantityFor(stock.getId(), tenantId);
        int available = stock.getStockedAmount() - reserved;
        if (quantity > available) {
            throw new ConflictException("insufficient stock for '" + stock.getName()
                    + "': requested " + quantity + ", available " + available);
        }

        StockReservation reservation = new StockReservation();
        reservation.setTenantId(tenantId);
        reservation.setId(UUID.randomUUID().toString());
        reservation.setProductStockId(stock.getId());
        reservation.setOrderId(orderId);
        reservation.setQuantity(quantity);
        reservation.setState(StockReservation.ACTIVE);
        reservation.setCreatedAt(OffsetDateTime.now());
        reservations.save(reservation);

        request.setState(RESERVED);
        events.publish("StockReservationCreateEvent", "stockReservation", request);
        return request;
    }

    /** Cancellation path: active reservations for the order stop counting. */
    @Transactional
    public int release(StockOperationDto request) {
        String orderId = refId(request.getRelatedOrder(), "relatedOrder");
        List<StockReservation> active = reservations.findByTenantIdAndOrderIdAndState(
                tenantScope.currentTenantId(), orderId, StockReservation.ACTIVE);
        for (StockReservation r : active) {
            r.setState(StockReservation.RELEASED);
        }
        reservations.saveAll(active);
        return active.size();
    }

    /** Completion path: the reserved units leave the shelf for good. */
    @Transactional
    public int consume(StockOperationDto request) {
        String orderId = refId(request.getRelatedOrder(), "relatedOrder");
        String tenantId = tenantScope.currentTenantId();
        List<StockReservation> active = reservations.findByTenantIdAndOrderIdAndState(
                tenantId, orderId, StockReservation.ACTIVE);
        for (StockReservation r : active) {
            ProductStock stock = stocks.findForUpdateById(r.getProductStockId(), tenantId)
                    .orElseThrow(() -> new IllegalStateException("reservation without stock row"));
            stock.setStockedAmount(stock.getStockedAmount() - r.getQuantity());
            stock.setLastUpdate(OffsetDateTime.now());
            stocks.save(stock);
            r.setState(StockReservation.COMPLETED);
        }
        reservations.saveAll(active);
        if (!active.isEmpty()) {
            events.publish("ProductStockConsumedEvent", "productStock",
                    Map.of("relatedOrder", Map.of("id", orderId), "reservations", active.size()));
        }
        return active.size();
    }

    private String refId(Map<String, Object> ref, String field) {
        Object id = ref == null ? null : ref.get("id");
        if (id == null) {
            throw new BadRequestException(field + ".id is required");
        }
        return String.valueOf(id);
    }
}
