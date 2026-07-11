package com.bss.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fan-out to every watching browser over Server-Sent Events, plus a short
 * replay buffer so a page that opens mid-demo still sees the last moves.
 */
@Component
public class FlowBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(FlowBroadcaster.class);
    private static final int REPLAY = 40;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Deque<Map<String, Object>> recent = new ArrayDeque<>();
    private final ObjectMapper objectMapper;

    public FlowBroadcaster(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        // Replay the recent tail so the graph is populated immediately.
        synchronized (recent) {
            for (Map<String, Object> event : recent) {
                send(emitter, "flow", event);
            }
        }
        return emitter;
    }

    public void broadcast(Map<String, Object> event) {
        synchronized (recent) {
            recent.addLast(event);
            while (recent.size() > REPLAY) {
                recent.removeFirst();
            }
        }
        for (SseEmitter emitter : emitters) {
            send(emitter, "flow", event);
        }
    }

    private void send(SseEmitter emitter, String name, Map<String, Object> event) {
        try {
            emitter.send(SseEmitter.event().name(name)
                    .data(objectMapper.writeValueAsString(event)));
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
        }
    }
}
