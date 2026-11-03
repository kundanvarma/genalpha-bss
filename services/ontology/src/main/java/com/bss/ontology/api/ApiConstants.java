package com.bss.ontology.api;

public final class ApiConstants {

    /** The registry's own face: concepts, actions, capabilities, components, explain, check, execute, MCP. */
    public static final String BASE_PATH = "/ontology/v1";

    /** Where a component describes itself at runtime (Ivan's principle 9): served here for the registry itself. */
    public static final String WELL_KNOWN = "/.well-known/genalpha-component.json";

    /** A2A 1.0 (#426): the Agent Card a partner's agent reads before delegating — anonymous by the protocol's design. */
    public static final String AGENT_CARD = "/.well-known/agent-card.json";

    /** A2A 1.0 JSON-RPC endpoint (token required, like MCP). */
    public static final String A2A_PATH = BASE_PATH + "/a2a";

    private ApiConstants() {
    }
}
