package com.bss.stock.controller;

import com.bss.stock.api.ApiConstants;
import com.bss.stock.dto.StockOperationDto;
import com.bss.stock.service.StockOperationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    public StockOperationController(StockOperationService service) {
        this.service = service;
    }

    @PostMapping("/reserveProductStock")
    public ResponseEntity<StockOperationDto> reserve(@RequestBody StockOperationDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reserve(request));
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
