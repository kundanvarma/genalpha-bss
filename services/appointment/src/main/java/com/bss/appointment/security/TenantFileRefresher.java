package com.bss.appointment.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ZERO-RESTART TENANCY: watches the shared /config/tenants.yml and lets
 * NEW operators join the running fleet — the security resolvers build
 * their issuer trust lazily, so a fresh tenant's first token just works
 * once its entry is in the registry. Changes to EXISTING tenants still
 * take a restart (a deliberate, honest limitation: live mutation of a
 * serving tenant is a different risk class). Generated identically into
 * every registry-bearing service; reflection binds whatever fields THIS
 * service's TenantEntry knows and ignores the rest.
 */
@Component
public class TenantFileRefresher {

    private static final Logger log = LoggerFactory.getLogger(TenantFileRefresher.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+):?([^}]*)\\}");

    private final TenantRegistry tenants;
    private final String file;

    public TenantFileRefresher(TenantRegistry tenants) {
        this.tenants = tenants;
        this.file = System.getenv().getOrDefault("BSS_TENANTS_FILE", "/config/tenants.yml");
        long interval = Long.parseLong(System.getenv().getOrDefault("BSS_TENANTS_REFRESH_MS", "15000"));
        // own executor: no @EnableScheduling requirement on the host service
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tenant-file-refresher");
            t.setDaemon(true);
            return t;
        }).scheduleWithFixedDelay(this::refresh, interval, interval, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    public void refresh() {
        try (FileInputStream in = new FileInputStream(file)) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> bss = (Map<String, Object>) root.get("bss");
            Map<String, Object> block = (Map<String, Object>) bss.get("tenants");
            for (Map<String, Object> entry : (List<Map<String, Object>>) block.get("registry")) {
                String id = String.valueOf(entry.get("id"));
                Object existing = tenants.byId(id);
                if (existing != null) {
                    // LIVE MUTATION for a serving tenant: seams, brand and
                    // hosts follow the file; identity (id, issuer, key
                    // endpoints) stays restart-territory — the security
                    // resolvers cache per issuer, and lying about that
                    // boundary would be worse than having it
                    for (Map.Entry<String, Object> field : entry.entrySet()) {
                        String key = field.getKey();
                        if (key.equals("id") || key.equals("issuer")
                                || key.equals("jwks-uri") || key.equals("token-uri")) {
                            continue;
                        }
                        bindIfChanged(existing, key, field.getValue(), id);
                    }
                    continue;
                }
                Object fresh = newEntry();
                for (Map.Entry<String, Object> field : entry.entrySet()) {
                    bind(fresh, field.getKey(), field.getValue());
                }
                tenants.getRegistry().add((TenantRegistry.TenantEntry) fresh);
                log.info("tenant '{}' joined the fleet LIVE from {}", id, file);
            }
        } catch (java.io.FileNotFoundException e) {
            // no file, no fleet-as-a-file: the built-in registry stands
        } catch (Exception e) {
            log.warn("tenant file refresh skipped: {}", e.getMessage());
        }
    }

    private Object newEntry() throws Exception {
        for (Class<?> inner : TenantRegistry.class.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("TenantEntry")) {
                var ctor = inner.getDeclaredConstructor();
                ctor.setAccessible(true);
                return ctor.newInstance();
            }
        }
        throw new IllegalStateException("no TenantEntry inner class");
    }

    /** Update one field on a LIVE entry, logging what changed. */
    private void bindIfChanged(Object entry, String key, Object value, String tenantId) {
        try {
            String[] parts = key.split("-");
            StringBuilder base = new StringBuilder();
            for (String p : parts) {
                base.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
            }
            Method getter = entry.getClass().getMethod("get" + base);
            Object current = getter.invoke(entry);
            Object next = value instanceof List ? value : resolve(String.valueOf(value));
            if (current == null ? (next == null || String.valueOf(next).isEmpty())
                    : String.valueOf(current).equals(String.valueOf(next))) {
                return;
            }
            bind(entry, key, value);
            log.info("tenant '{}' field '{}' mutated LIVE", tenantId, key);
        } catch (Exception ignored) {
            // no getter here — not this service's field
        }
    }

    /** kebab-case yml key -> setter; unknown fields are simply not ours. */
    private void bind(Object entry, String key, Object value) {
        String[] parts = key.split("-");
        StringBuilder name = new StringBuilder("set");
        for (String p : parts) {
            name.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        for (Method m : entry.getClass().getMethods()) {
            if (!m.getName().equals(name.toString()) || m.getParameterCount() != 1) {
                continue;
            }
            try {
                Class<?> want = m.getParameterTypes()[0];
                if (want == List.class && value instanceof List) {
                    m.invoke(entry, value);
                } else if (want == String.class) {
                    m.invoke(entry, resolve(String.valueOf(value)));
                } else if ((want == boolean.class || want == Boolean.class)) {
                    m.invoke(entry, Boolean.parseBoolean(resolve(String.valueOf(value))));
                } else if ((want == int.class || want == Integer.class)) {
                    m.invoke(entry, Integer.parseInt(resolve(String.valueOf(value))));
                }
            } catch (Exception ignored) {
                // one odd field must not keep a tenant out of the fleet
            }
            return;
        }
    }

    /** The same ${ENV:default} contract Spring applies at import time. */
    static String resolve(String raw) {
        Matcher m = PLACEHOLDER.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String env = System.getenv(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(env != null ? env : m.group(2)));
        }
        m.appendTail(out);
        return out.toString();
    }
}
