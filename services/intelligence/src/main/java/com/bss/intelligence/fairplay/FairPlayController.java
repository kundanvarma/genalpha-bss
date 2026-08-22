package com.bss.intelligence.fairplay;

import com.bss.intelligence.api.ApiConstants;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The fair-play door: sweep the base for oversized plans. Product owner's
 *  tool (catalog:write via the /simulate gate's sibling rule below). */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/fairPlay")
public class FairPlayController {

    private final FairPlayService fairPlay;

    public FairPlayController(FairPlayService fairPlay) {
        this.fairPlay = fairPlay;
    }

    @PostMapping("/sweep")
    public ResponseEntity<Map<String, Object>> sweep(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String partyId) {
        return ResponseEntity.ok(fairPlay.sweep(partyId));
    }
}
