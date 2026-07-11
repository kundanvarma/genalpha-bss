package com.bss.flow;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
public class FlowController {

    private final FlowBroadcaster broadcaster;

    public FlowController(FlowBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    /** The live event stream every Mission Control page subscribes to. */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return broadcaster.subscribe();
    }

    /** The static choreography the page draws its graph from. */
    @GetMapping("/api/graph")
    public Map<String, Object> graph() {
        return Map.of(
                "reactors", Choreography.REACTORS,
                "aiAgents", Choreography.AI_AGENTS,
                "producers", List.of("product-ordering", "billing", "trouble-ticket",
                        "shopping-cart", "agreement", "appointment", "intelligence",
                        "service-orchestration", "payment", "quote", "assurance"),
                "consumers", List.of("communication", "campaign", "service-orchestration"));
    }
}
