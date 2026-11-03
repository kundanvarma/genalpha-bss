package com.bss.gateway.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The keys of the agent operators this deployment trusts, by key id (the JWK
 * thumbprint). Static keys come from configuration; directory keys are fetched
 * from the configured directory URL, cached for {@code directory-ttl}, and
 * re-fetched no faster than {@code directory-min-refresh} — so a flood of
 * made-up key ids costs at most one fetch per directory per interval.
 *
 * <p>The {@code Signature-Agent} a request names is only ever used to pick
 * among the configured operators. It is never fetched: a URL that matches no
 * operator means "unknown agent", not "go and ask it".
 */
public class AgentKeyDirectory {

    private static final Logger log = LoggerFactory.getLogger(AgentKeyDirectory.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A key the gateway trusts and whose operator it can name. */
    public record TrustedKey(String operator, String keyid, PublicKey key) {
    }

    /** Fetches a directory document; the production one is a WebClient, tests pass their own. */
    @FunctionalInterface
    public interface Fetcher {
        Mono<String> get(String url);
    }

    private record Operator(String name, String signatureAgent, String directory, Map<String, TrustedKey> staticKeys) {
    }

    private record Cached(Map<String, TrustedKey> keys, Instant fetchedAt, Instant attemptedAt) {
    }

    private final List<Operator> operators;
    private final Fetcher fetcher;
    private final Clock clock;
    private final WebBotAuthProperties props;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public AgentKeyDirectory(WebBotAuthProperties props, Fetcher fetcher, Clock clock) {
        this.props = props;
        this.fetcher = fetcher;
        this.clock = clock;
        List<Operator> ops = new ArrayList<>();
        for (WebBotAuthProperties.Operator o : props.getOperators()) {
            if (o.getName() == null || o.getName().isBlank()) {
                log.warn("web-bot-auth: an operator without a name is ignored");
                continue;
            }
            String directory = o.getDirectory();
            if (directory != null && !directory.isBlank() && !acceptableDirectory(directory)) {
                log.warn("web-bot-auth: operator {} directory must be https; ignored", o.getName());
                directory = null;
            }
            Map<String, TrustedKey> keys = new HashMap<>();
            for (WebBotAuthProperties.Jwk jwk : o.getKeys()) {
                TrustedKey k = toKey(o.getName().trim(), jwk.getKty(), jwk.getCrv(), jwk.getX());
                if (k != null) {
                    keys.put(k.keyid(), k);
                }
            }
            ops.add(new Operator(o.getName().trim(), normalise(o.getSignatureAgent()),
                    directory == null || directory.isBlank() ? null : directory.trim(), Map.copyOf(keys)));
        }
        this.operators = List.copyOf(ops);
    }

    public boolean isEmpty() {
        return operators.isEmpty();
    }

    /**
     * The trusted key with this id, from the operator the {@code Signature-Agent}
     * names (or from any configured operator when the request names none).
     * Empty when the URL is not one we know or the key id belongs to nobody.
     */
    public Mono<Optional<TrustedKey>> find(String keyid, String signatureAgent) {
        List<Operator> candidates = candidates(signatureAgent);
        if (candidates.isEmpty()) {
            return Mono.just(Optional.empty());
        }
        Instant now = clock.instant();
        Optional<TrustedKey> known = lookup(candidates, keyid, now);
        if (known.isPresent()) {
            return Mono.just(known);
        }
        List<Operator> due = candidates.stream()
                .filter(o -> o.directory() != null && dueForFetch(o.directory(), now)).toList();
        if (due.isEmpty()) {
            return Mono.just(Optional.empty());
        }
        return Flux.fromIterable(due)
                .flatMap(o -> refresh(o, now))
                .then(Mono.fromSupplier(() -> lookup(candidates, keyid, clock.instant())));
    }

    private List<Operator> candidates(String signatureAgent) {
        String wanted = normalise(signatureAgent);
        if (wanted == null) {
            return operators;
        }
        return operators.stream().filter(o -> wanted.equals(o.signatureAgent())
                || (o.directory() != null && wanted.equals(origin(o.directory())))).toList();
    }

    private Optional<TrustedKey> lookup(List<Operator> candidates, String keyid, Instant now) {
        for (Operator o : candidates) {
            TrustedKey k = o.staticKeys().get(keyid);
            if (k != null) {
                return Optional.of(k);
            }
            Cached c = o.directory() == null ? null : cache.get(o.directory());
            if (c != null && c.fetchedAt() != null
                    && c.fetchedAt().plus(props.getDirectoryTtl()).isAfter(now)) {
                k = c.keys().get(keyid);
                if (k != null) {
                    return Optional.of(k);
                }
            }
        }
        return Optional.empty();
    }

    private boolean dueForFetch(String directory, Instant now) {
        Cached c = cache.get(directory);
        return c == null || c.attemptedAt().plus(props.getDirectoryMinRefresh()).isBefore(now);
    }

    private Mono<Void> refresh(Operator o, Instant now) {
        // mark the attempt first, so concurrent misses do not all fetch
        Cached previous = cache.get(o.directory());
        cache.put(o.directory(), new Cached(previous == null ? Map.of() : previous.keys(),
                previous == null ? null : previous.fetchedAt(), now));
        return fetcher.get(o.directory())
                .map(body -> parseDirectory(o.name(), body))
                .doOnNext(keys -> cache.put(o.directory(), new Cached(keys, clock.instant(), now)))
                .onErrorResume(e -> {
                    log.warn("web-bot-auth: directory of {} could not be read: {}", o.name(), e.toString());
                    return Mono.empty();
                })
                .then();
    }

    static Map<String, TrustedKey> parseDirectory(String operator, String body) {
        JsonNode root = JSON.readTree(body);
        Map<String, TrustedKey> keys = new HashMap<>();
        for (JsonNode jwk : root.path("keys")) {
            TrustedKey k = toKey(operator, jwk.path("kty").asString(""), jwk.path("crv").asString(""),
                    jwk.path("x").asString(""));
            if (k != null) {
                keys.put(k.keyid(), k);
            }
        }
        return Map.copyOf(keys);
    }

    private static TrustedKey toKey(String operator, String kty, String crv, String x) {
        if (!"OKP".equals(kty) || !"Ed25519".equals(crv) || x == null || x.isBlank()) {
            return null; // only Ed25519 is profiled here; other keys are skipped, not fatal
        }
        try {
            return new TrustedKey(operator, Ed25519Keys.thumbprint(x.trim()), Ed25519Keys.fromX(x.trim()));
        } catch (IllegalArgumentException e) {
            log.warn("web-bot-auth: a key of {} is not a valid Ed25519 key; skipped", operator);
            return null;
        }
    }

    private boolean acceptableDirectory(String url) {
        try {
            String scheme = URI.create(url.trim()).getScheme();
            return "https".equalsIgnoreCase(scheme)
                    || (props.isAllowInsecureDirectories() && "http".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Lower-case scheme and host, no trailing slash: how two spellings of one origin compare equal. */
    static String normalise(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u.toLowerCase(java.util.Locale.ROOT);
    }

    private static String origin(String url) {
        try {
            URI u = URI.create(url);
            return normalise(u.getScheme() + "://" + u.getRawAuthority());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
