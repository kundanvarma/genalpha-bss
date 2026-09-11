package com.bss.ontology.api;

public final class ApiConstants {

    /** The registry's own face: concepts, actions, capabilities, components, explain, check, execute, MCP. */
    public static final String BASE_PATH = "/ontology/v1";

    /** Where a component describes itself at runtime (Ivan's principle 9): served here for the registry itself. */
    public static final String WELL_KNOWN = "/.well-known/genalpha-component.json";

    private ApiConstants() {
    }
}
