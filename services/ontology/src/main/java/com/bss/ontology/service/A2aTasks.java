package com.bss.ontology.service;

import com.bss.ontology.dto.A2aMessages.Task;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The A2A tasks this process has answered, so {@code GetTask} can return one by id.
 * A task belongs to the tenant AND the person whose token delegated it: anyone
 * else asking for the id — another tenant, another customer, another partner's
 * agent with a different person's token — is told it does not exist, the same
 * 404-not-403 rule as every other cross-tenant read.
 *
 * <p>In memory, bounded (the oldest are forgotten first). The durable record of
 * what was DONE is the receipt store and the decision log, not this map; a task
 * id that outlived a restart reads as not found. Named in docs/a2a.md.
 */
@Component
public class A2aTasks {

    static final int CAPACITY = 2000;

    private record Owned(String tenant, String subject, Task task) {
    }

    private final Map<String, Owned> tasks = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Owned> eldest) {
            return size() > CAPACITY;
        }
    };

    public synchronized void put(String tenant, String subject, Task task) {
        tasks.put(task.id(), new Owned(tenant, subject, task));
    }

    public synchronized Optional<Task> get(String id, String tenant, String subject) {
        Owned o = id == null ? null : tasks.get(id);
        if (o == null || !java.util.Objects.equals(o.tenant(), tenant) || !java.util.Objects.equals(o.subject(), subject)) {
            return Optional.empty();
        }
        return Optional.of(o.task());
    }
}
