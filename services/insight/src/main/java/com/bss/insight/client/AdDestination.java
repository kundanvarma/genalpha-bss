package com.bss.insight.client;

import java.util.List;

/**
 * One ad/social platform an audience can be activated to. The connector
 * abstraction: the same hashed identifiers, formatted per-platform (Meta Custom
 * Audiences, Google Customer Match, …). Add a platform = add an AdDestination,
 * no change to the activation flow. In prod each carries its own OAuth token
 * from the secret store; here they hit mock endpoints.
 */
public interface AdDestination {
    /** platform key used in the activate request (meta | google | …). */
    String name();

    boolean enabled();

    /** Push already-hashed (SHA-256) identifiers; return the count accepted. */
    int push(String externalAudienceId, List<String> hashedEmails);
}
