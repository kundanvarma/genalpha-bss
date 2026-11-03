package com.bss.gateway.agent;

import com.bss.gateway.agent.StructuredFields.Bytes;
import com.bss.gateway.agent.StructuredFields.InnerList;
import com.bss.gateway.agent.StructuredFields.Item;
import com.bss.gateway.agent.StructuredFields.Member;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Verifies an HTTP Message Signature (RFC 9421) as profiled by Web Bot Auth
 * (draft-ietf-webbotauth-httpsig-protocol-01): the {@code Signature-Input}
 * member tagged {@code web-bot-auth} must carry {@code created}, {@code expires}
 * and {@code keyid}; it must cover {@code @authority} or {@code @target-uri};
 * when a {@code Signature-Agent} member shares its label, it must be covered too;
 * the key id must be a trusted operator's key; and the Ed25519 signature over the
 * signature base must hold.
 */
public class WebBotAuthVerifier {

    public static final String TAG = "web-bot-auth";

    /** What the gateway concluded. */
    public sealed interface Outcome {
    }

    /** No signature in the Web Bot Auth profile at all — an ordinary request. */
    public record Absent() implements Outcome {
    }

    /** A trusted operator's key signed this request, within its window. */
    public record Verified(String operator, String keyid) implements Outcome {
    }

    /** It tried to be a signed agent and is not one; the reason is for logs and metrics only. */
    public record Refused(Reason reason) implements Outcome {
    }

    public enum Reason {
        MALFORMED, MISSING_PARAMETER, UNSUPPORTED_ALGORITHM, NOT_YET_VALID, EXPIRED, WINDOW_TOO_LONG,
        AUTHORITY_NOT_COVERED, AGENT_NOT_COVERED, UNSUPPORTED_COMPONENT, UNKNOWN_KEY, BAD_SIGNATURE
    }

    private final AgentKeyDirectory keys;
    private final WebBotAuthProperties props;
    private final Clock clock;

    public WebBotAuthVerifier(AgentKeyDirectory keys, WebBotAuthProperties props, Clock clock) {
        this.keys = keys;
        this.props = props;
        this.clock = clock;
    }

    public Mono<Outcome> verify(ServerHttpRequest request) {
        HttpHeaders h = request.getHeaders();
        String inputHeader = joined(h, "Signature-Input");
        String sigHeader = joined(h, "Signature");
        if (inputHeader == null || sigHeader == null) {
            return Mono.just(inputHeader == null && sigHeader == null ? new Absent() : refusedIfTagged(inputHeader));
        }
        Map<String, Member> inputs;
        Map<String, Member> sigs;
        try {
            inputs = StructuredFields.parseDictionary(inputHeader);
            sigs = StructuredFields.parseDictionary(sigHeader);
        } catch (IllegalArgumentException e) {
            return Mono.just(inputHeader.contains(TAG) ? new Refused(Reason.MALFORMED) : new Absent());
        }
        String label = null;
        InnerList input = null;
        for (Map.Entry<String, Member> e : inputs.entrySet()) {
            if (e.getValue() instanceof InnerList l && TAG.equals(l.params().get("tag"))) {
                label = e.getKey();
                input = l;
                break;
            }
        }
        if (input == null) {
            return Mono.just(new Absent()); // a signature in some other profile is not ours to judge
        }
        return check(request, label, input, sigs.get(label));
    }

    private static Outcome refusedIfTagged(String inputHeader) {
        return inputHeader != null && inputHeader.contains(TAG) ? new Refused(Reason.MALFORMED) : new Absent();
    }

    private Mono<Outcome> check(ServerHttpRequest request, String label, InnerList input, Member sigMember) {
        if (!(sigMember instanceof Item sigItem) || !(sigItem.value() instanceof Bytes signature)) {
            return refused(Reason.MALFORMED);
        }
        Object created = input.params().get("created");
        Object expires = input.params().get("expires");
        Object keyid = input.params().get("keyid");
        if (!(created instanceof Long c) || !(expires instanceof Long x) || !(keyid instanceof String kid)
                || kid.isBlank()) {
            return refused(Reason.MISSING_PARAMETER);
        }
        Object alg = input.params().get("alg");
        if (alg != null && !"ed25519".equals(alg)) {
            return refused(Reason.UNSUPPORTED_ALGORITHM);
        }
        long now = clock.instant().getEpochSecond();
        long skew = props.getClockSkew().toSeconds();
        if (c > now + skew) {
            return refused(Reason.NOT_YET_VALID);
        }
        if (x <= now - skew) {
            return refused(Reason.EXPIRED);
        }
        if (x <= c || x - c > props.getMaxValidity().toSeconds()) {
            return refused(Reason.WINDOW_TOO_LONG);
        }

        // which components, and is the profile's minimum covered?
        Set<String> covered = new HashSet<>();
        for (Item component : input.items()) {
            if (!(component.value() instanceof String)) {
                return refused(Reason.MALFORMED);
            }
            if (!covered.add(StructuredFields.serialize(component))) {
                return refused(Reason.MALFORMED); // the same component twice
            }
        }
        boolean authority = input.items().stream().anyMatch(i -> "@authority".equals(i.value()) || "@target-uri".equals(i.value()));
        if (!authority) {
            return refused(Reason.AUTHORITY_NOT_COVERED);
        }

        // Signature-Agent: the member under this label (or the legacy bare string) must be signed
        String agentUrl = null;
        String agentHeader = joined(request.getHeaders(), "Signature-Agent");
        if (agentHeader != null) {
            AgentRef ref = agentRef(agentHeader, label);
            if (ref == null) {
                return refused(Reason.MALFORMED);
            }
            if (ref.url() != null) {
                agentUrl = ref.url();
                if (!coversAgent(input.items(), ref.dictionary(), label)) {
                    return refused(Reason.AGENT_NOT_COVERED);
                }
            }
        }

        String base;
        try {
            base = signatureBase(request, input);
        } catch (IllegalArgumentException e) {
            return refused(Reason.UNSUPPORTED_COMPONENT);
        }
        byte[] message = base.getBytes(StandardCharsets.UTF_8);
        return keys.find(kid, agentUrl).map(found -> {
            if (found.isEmpty()) {
                return (Outcome) new Refused(Reason.UNKNOWN_KEY);
            }
            AgentKeyDirectory.TrustedKey key = found.get();
            return Ed25519Keys.verify(key.key(), message, signature.value())
                    ? new Verified(key.operator(), key.keyid())
                    : new Refused(Reason.BAD_SIGNATURE);
        });
    }

    private record AgentRef(String url, boolean dictionary) {
    }

    /** The URL under {@code label}; null url when the dictionary has no such member; null when unreadable. */
    private static AgentRef agentRef(String header, String label) {
        String trimmed = header.trim();
        if (trimmed.startsWith("\"")) { // legacy form: one bare string, treated as the label's member
            try {
                Map<String, Member> one = StructuredFields.parseDictionary("x=" + trimmed);
                return one.get("x") instanceof Item i && i.value() instanceof String s ? new AgentRef(s, false) : null;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        try {
            Member m = StructuredFields.parseDictionary(trimmed).get(label);
            if (m == null || !m.params().values().isEmpty()) {
                // draft §5.2.1: a member carrying parameters MUST be ignored
                return new AgentRef(null, true);
            }
            return m instanceof Item i && i.value() instanceof String s ? new AgentRef(s, true) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean coversAgent(List<Item> components, boolean dictionary, String label) {
        return components.stream().anyMatch(i -> "signature-agent".equals(i.value())
                && (dictionary ? label.equals(i.params().get("key")) && i.params().values().size() == 1
                        : i.params().values().isEmpty()));
    }

    /** RFC 9421 §2.5: one line per covered component, then the {@code @signature-params} line. */
    static String signatureBase(ServerHttpRequest request, InnerList input) {
        StringBuilder sb = new StringBuilder();
        for (Item component : input.items()) {
            sb.append(StructuredFields.serialize(component)).append(": ")
                    .append(componentValue(request, component)).append('\n');
        }
        return sb.append("\"@signature-params\": ").append(StructuredFields.serialize(input)).toString();
    }

    private static String componentValue(ServerHttpRequest request, Item component) {
        String name = (String) component.value();
        Map<String, Object> params = component.params().values();
        if (name.startsWith("@")) {
            if (!params.isEmpty()) {
                throw new IllegalArgumentException("derived component parameters");
            }
            return switch (name) {
                case "@method" -> request.getMethod().name();
                case "@authority" -> authority(request);
                case "@scheme" -> scheme(request);
                case "@target-uri" -> scheme(request) + "://" + authority(request) + request.getURI().getRawPath()
                        + (request.getURI().getRawQuery() == null ? "" : "?" + request.getURI().getRawQuery());
                case "@path" -> request.getURI().getRawPath().isEmpty() ? "/" : request.getURI().getRawPath();
                case "@query" -> "?" + (request.getURI().getRawQuery() == null ? "" : request.getURI().getRawQuery());
                default -> throw new IllegalArgumentException("unsupported derived component " + name);
            };
        }
        if (!name.equals(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("field names are lower case");
        }
        String value = joined(request.getHeaders(), name);
        if (value == null) {
            throw new IllegalArgumentException("covered field is absent");
        }
        if (params.isEmpty()) {
            return value;
        }
        if (params.size() == 1 && params.get("key") instanceof String key) {
            Member member = StructuredFields.parseDictionary(value).get(key);
            if (member == null) {
                throw new IllegalArgumentException("dictionary member is absent");
            }
            return StructuredFields.serialize(member);
        }
        throw new IllegalArgumentException("unsupported field parameters");
    }

    /** Host and port as the client addressed us, lower case, default port left off. */
    static String authority(ServerHttpRequest request) {
        String host = request.getHeaders().getFirst(HttpHeaders.HOST);
        if (host == null || host.isBlank()) {
            host = request.getURI().getRawAuthority();
        }
        host = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        if (host.endsWith(":443") || host.endsWith(":80")) {
            host = host.substring(0, host.lastIndexOf(':'));
        }
        return host;
    }

    private static String scheme(ServerHttpRequest request) {
        String forwarded = request.getHeaders().getFirst("X-Forwarded-Proto");
        String s = forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim()
                : request.getURI().getScheme();
        return s == null ? "http" : s.toLowerCase(Locale.ROOT);
    }

    /** All values of a field, trimmed and joined with ", " (RFC 9421 §2.1); null when absent. */
    private static String joined(HttpHeaders headers, String name) {
        List<String> values = headers.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return String.join(", ", values.stream().map(String::trim).toList());
    }

    private static Mono<Outcome> refused(Reason reason) {
        return Mono.just(new Refused(reason));
    }
}
