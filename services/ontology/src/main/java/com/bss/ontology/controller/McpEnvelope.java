package com.bss.ontology.controller;

import com.bss.ontology.dto.McpMessages;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * The MCP envelope rules, apart from what any method does: which protocol
 * version a request speaks, and whether the HTTP headers a gateway routes on
 * say the same thing as the body the server executes.
 *
 * Two eras on one endpoint (MCP 2026-07-28, "dual-era"). A MODERN request
 * carries its version and capabilities in {@code params._meta} on every call
 * — no session, no handshake — and must mirror them in {@code MCP-Protocol-Version},
 * {@code Mcp-Method} and {@code Mcp-Name}. A LEGACY client (2025-06-18) opens
 * with {@code initialize} and sends no {@code _meta}; its headers, when it sends
 * them, are still held to the body, because a gateway that routes on a header
 * and a server that executes the body must never disagree about what ran.
 */
final class McpEnvelope {

    static final String LATEST = "2026-07-28";
    static final String LEGACY = "2025-06-18";
    /** Newest first: what server/discover and an UnsupportedProtocolVersion error list. */
    static final List<String> SUPPORTED = List.of(LATEST, LEGACY);

    static final String META_VERSION = "io.modelcontextprotocol/protocolVersion";
    static final String META_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";

    static final String HEADER_VERSION = "MCP-Protocol-Version";
    static final String HEADER_METHOD = "Mcp-Method";
    static final String HEADER_NAME = "Mcp-Name";

    static final int INVALID_PARAMS = -32602;
    static final int HEADER_MISMATCH = -32020;
    static final int UNSUPPORTED_VERSION = -32022;

    private static final String B64_OPEN = "=?base64?";
    private static final String B64_CLOSE = "?=";

    private McpEnvelope() {
    }

    /** How a request is to be served: modern requests get {@code resultType} and server identity on every result. */
    record Era(boolean modern, String version) {
    }

    /** A request refused before any method runs: the HTTP status the transport requires, and the JSON-RPC error. */
    static final class Refusal extends RuntimeException {
        final int status;
        final int code;
        final transient Object data;

        Refusal(int status, int code, String message, Object data) {
            super(message);
            this.status = status;
            this.code = code;
            this.data = data;
        }
    }

    /**
     * The version a legacy {@code initialize} settles on: the client's own when we
     * speak it, else the newest version a handshake can lead to. 2026-07-28 has no
     * handshake, so a legacy client asking for something we do not speak is offered
     * 2025-06-18 — the one it can actually continue with — rather than a version
     * whose first rule is "there is no initialize".
     */
    static String negotiate(String requested) {
        return requested != null && SUPPORTED.contains(requested) ? requested : LEGACY;
    }

    /** Check one request's envelope; throws {@link Refusal} when it must not be served. */
    static Era validate(JsonNode req, HttpServletRequest http) {
        String method = req.path("method").asString("");
        JsonNode params = req.path("params");
        JsonNode meta = params.path("_meta");
        String metaVersion = meta.path(META_VERSION).isString() ? meta.path(META_VERSION).asString() : null;
        String headerVersion = http.getHeader(HEADER_VERSION);
        String headerMethod = http.getHeader(HEADER_METHOD);
        String headerName = http.getHeader(HEADER_NAME);
        String bodyName = nameOf(method, params);

        if (metaVersion != null) {
            if (!SUPPORTED.contains(metaVersion)) {
                throw unsupported(metaVersion);
            }
            if (headerVersion == null) {
                throw mismatch(HEADER_VERSION + " header is required");
            }
            if (!headerVersion.equals(metaVersion)) {
                throw mismatch(HEADER_VERSION + " header value '" + headerVersion + "' does not match body value '" + metaVersion + "'");
            }
            if (LATEST.equals(metaVersion) && !meta.has(META_CAPABILITIES)) {
                throw new Refusal(400, INVALID_PARAMS, "_meta." + META_CAPABILITIES + " is required on every " + LATEST + " request", null);
            }
            if (headerMethod == null) {
                throw mismatch(HEADER_METHOD + " header is required");
            }
            if (bodyName != null && headerName == null) {
                throw mismatch(HEADER_NAME + " header is required for " + method);
            }
        } else {
            if (headerVersion != null && !SUPPORTED.contains(headerVersion)) {
                throw unsupported(headerVersion);
            }
            if (LATEST.equals(headerVersion) && !"initialize".equals(method)) {
                throw new Refusal(400, INVALID_PARAMS, "a " + LATEST + " request carries _meta." + META_VERSION, null);
            }
        }
        if (headerMethod != null && !headerMethod.equals(method)) {
            throw mismatch(HEADER_METHOD + " header value '" + headerMethod + "' does not match body value '" + method + "'");
        }
        if (headerName != null) {
            String decoded = decode(headerName);
            if (bodyName == null) {
                throw mismatch(HEADER_NAME + " header sent for " + method + ", which names nothing");
            }
            if (!decoded.equals(bodyName)) {
                throw mismatch(HEADER_NAME + " header value '" + decoded + "' does not match body value '" + bodyName + "'");
            }
        }
        boolean modern = LATEST.equals(metaVersion);
        return new Era(modern, metaVersion != null ? metaVersion : headerVersion != null ? headerVersion : LEGACY);
    }

    /** What {@code Mcp-Name} mirrors: the tool or prompt name, or the resource URI. */
    static String nameOf(String method, JsonNode params) {
        return switch (method) {
            case "tools/call", "prompts/get" -> params.path("name").asString("");
            case "resources/read" -> params.path("uri").asString("");
            default -> null;
        };
    }

    /** A header value outside plain ASCII travels as {@code =?base64?…?=}; compare it decoded. */
    static String decode(String header) {
        if (header.startsWith(B64_OPEN) && header.endsWith(B64_CLOSE) && header.length() >= B64_OPEN.length() + B64_CLOSE.length()) {
            try {
                return new String(Base64.getDecoder().decode(header.substring(B64_OPEN.length(), header.length() - B64_CLOSE.length())),
                        StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw mismatch(HEADER_NAME + " header is not valid base64");
            }
        }
        return header;
    }

    private static Refusal mismatch(String what) {
        return new Refusal(400, HEADER_MISMATCH, "Header mismatch: " + what, null);
    }

    private static Refusal unsupported(String requested) {
        return new Refusal(400, UNSUPPORTED_VERSION, "Unsupported protocol version",
                new McpMessages.UnsupportedVersion(SUPPORTED, requested));
    }
}
