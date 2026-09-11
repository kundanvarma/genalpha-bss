package com.bss.ontology.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calls a capability on its component. Two identities only: the CALLER's own
 * bearer (the normal case — a customer reads and changes their own line, a
 * clerk what their role allows) and, for the policy pre-check alone, the
 * registry's machine identity. Never throws on a downstream status: the
 * verdict is the caller's to read, in words.
 */
@Component
public class ComponentClient {

    public record Reply(int status, JsonNode body, String raw) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final RestClient.Builder builder;
    private final ObjectMapper json;
    private final OntologyProperties props;
    private final MachineTokenInterceptor machine;
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();
    private final Map<String, RestClient> machineClients = new ConcurrentHashMap<>();

    public ComponentClient(RestClient.Builder builder, ObjectMapper json, OntologyProperties props,
            MachineTokenInterceptor machine) {
        this.builder = builder;
        this.json = json;
        this.props = props;
        this.machine = machine;
    }

    /** With the caller's bearer. {@code pathVars} fill {id}-style segments; {@code headers} may carry X-Channel / X-Tenant-Id. */
    public Reply call(String component, String method, String path, Map<String, String> pathVars,
            Map<String, String> query, Object body, String bearer, Map<String, String> headers) {
        RestClient client = clients.computeIfAbsent(props.baseOf(component), base -> builder.clone().baseUrl(base).build());
        return exchange(client, method, path, pathVars, query, body, bearer, headers);
    }

    /** With the registry's own machine token (policy evaluation only). */
    public Reply callAsMachine(String component, String method, String path, Map<String, String> query, Object body,
            Map<String, String> headers) {
        RestClient client = machineClients.computeIfAbsent(props.baseOf(component),
                base -> builder.clone().baseUrl(base).requestInterceptor(machine).build());
        return exchange(client, method, path, Map.of(), query, body, null, headers);
    }

    private Reply exchange(RestClient client, String method, String path, Map<String, String> pathVars,
            Map<String, String> query, Object body, String bearer, Map<String, String> headers) {
        String filled = path;
        for (Map.Entry<String, String> v : pathVars.entrySet()) {
            filled = filled.replace("{" + v.getKey() + "}", v.getValue());
        }
        final String resolved = filled;
        try {
            RestClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(method))
                    .uri(uri -> {
                        uri.path(resolved);
                        query.forEach(uri::queryParam);
                        return uri.build();
                    });
            if (bearer != null && !bearer.isBlank()) {
                spec.header("Authorization", "Bearer " + bearer);
            }
            headers.forEach((k, v) -> {
                if (v != null && !v.isBlank()) {
                    spec.header(k, v);
                }
            });
            if (body != null) {
                spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return spec.exchange((req, res) -> {
                byte[] bytes = res.getBody().readAllBytes();
                String raw = bytes.length == 0 ? "" : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                return new Reply(res.getStatusCode().value(), parse(raw), raw);
            }, false);
        } catch (Exception e) {
            return new Reply(-1, MissingNode.getInstance(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            return MissingNode.getInstance();
        }
    }
}
