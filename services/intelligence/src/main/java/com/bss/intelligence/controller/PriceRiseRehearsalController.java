package com.bss.intelligence.controller;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.service.PriceRiseRehearsalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/** The rehearsal door: the price-rise letter, counted before it exists. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/priceRiseRehearsal")
public class PriceRiseRehearsalController {

    private final PriceRiseRehearsalService rehearsal;

    public PriceRiseRehearsalController(PriceRiseRehearsalService rehearsal) {
        this.rehearsal = rehearsal;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> rehearse(@RequestParam String offeringName,
            @RequestParam(defaultValue = "10") BigDecimal percent) {
        return ResponseEntity.ok(rehearsal.rehearse(offeringName, percent));
    }
}
