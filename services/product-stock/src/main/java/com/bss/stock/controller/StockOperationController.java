package com.bss.stock.controller;

import com.bss.stock.api.ApiConstants;
import com.bss.stock.dto.StockOperationDto;
import com.bss.stock.service.ReserveProductStockService;
import com.bss.stock.service.StockOperationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TMF687-style task resources tying stock to the order lifecycle.
 * Insufficient stock is a 409, not a 400: the request was fine, the shelf
 * disagreed.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class StockOperationController {

    private final StockOperationService service;
    private final ReserveProductStockService reserveResource;
    private final com.bss.stock.api.FieldSelector fieldSelector;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StockOperationController(StockOperationService service, ReserveProductStockService reserveResource,
            com.bss.stock.api.FieldSelector fieldSelector) {
        this.service = service;
        this.reserveResource = reserveResource;
        this.fieldSelector = fieldSelector;
    }

    /**
     * Dual-shape reserve. TMF687 models ReserveProductStock as a resource:
     * a body carrying {@code reserveProductStockItem} creates and returns one.
     * The app's order pipeline posts {productOffering, quantity, relatedOrder}
     * — the task form — which reserves against the shelf as before.
     */
    @PostMapping("/reserveProductStock")
    public ResponseEntity<?> reserve(@RequestBody Map<String, Object> body) {
        if (body.containsKey("reserveProductStockItem")) {
            return ResponseEntity.status(HttpStatus.CREATED).body(reserveResource.create(body));
        }
        StockOperationDto request = objectMapper.convertValue(body, StockOperationDto.class);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reserve(request));
    }

    @GetMapping("/reserveProductStock")
    public ResponseEntity<List<?>> listReserves(
            @RequestParam(name = "fields", required = false) String fields,
            @RequestParam Map<String, String> allParams) {
        Map<String, String> filters = new HashMap<>(allParams);
        filters.keySet().removeAll(List.of("offset", "limit", "fields", "sort"));
        List<Map<String, Object>> items = reserveResource.findAll(filters);
        return ResponseEntity.ok(fields == null ? items : fieldSelector.select(items, fields));
    }

    @GetMapping("/reserveProductStock/{id}")
    public ResponseEntity<Map<String, Object>> getReserve(@PathVariable("id") String id) {
        return ResponseEntity.ok(reserveResource.findById(id));
    }

    @PostMapping("/releaseProductStock")
    public ResponseEntity<Map<String, Object>> release(@RequestBody StockOperationDto request) {
        int released = service.release(request);
        return ResponseEntity.ok(Map.of("state", "released", "reservations", released));
    }

    @PostMapping("/consumeProductStock")
    public ResponseEntity<Map<String, Object>> consume(@RequestBody StockOperationDto request) {
        int consumed = service.consume(request);
        return ResponseEntity.ok(Map.of("state", "completed", "reservations", consumed));
    }
}
