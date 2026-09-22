package com.bss.intelligence.sim;

import com.bss.intelligence.api.ApiConstants;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The commercial simulator's door: run a price-change simulation, read the
 *  saved reports. A product owner's tool (catalog:write), like the advisor. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/simulate/priceChange")
public class PriceSimController {

    private final PriceSimService sim;

    public PriceSimController(PriceSimService sim) {
        this.sim = sim;
    }

    @PostMapping
    public ResponseEntity<PriceSimReportView> simulate(@RequestBody PriceSimRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sim.simulate(request));
    }

    @GetMapping
    public ResponseEntity<List<SavedReport>> list() {
        return ResponseEntity.ok(sim.list());
    }
}
