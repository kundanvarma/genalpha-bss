package com.bss.userroles.service;

import com.bss.userroles.dto.IdpRole;
import com.bss.userroles.dto.IdpUser;

import java.util.List;

/**
 * The slice of an IdP's admin API this component needs. One implementation
 * per IdP family; dev ships Keycloak. The tenant is decided by the CALLER's
 * verified issuer — a tenant admin can only ever manage their own realm.
 * The answers are the house projections ({@link IdpRole}, {@link IdpUser}):
 * each adapter maps its vendor's document into them, nothing above the
 * seam sees a vendor shape.
 */
public interface IdpAdminClient {

    /** A re-minted realm invalidates cached machine tokens; IdPs without
     * a cache simply ignore this. */
    default void evictTokens(String tenantId) {
    }

    List<IdpRole> realmRoles(String tenantId);

    List<IdpUser> users(String tenantId, String username);

    /**
     * Create a login in the tenant's realm and return its IdP-assigned id
     * (the future token subject). A {@code temporary} password must be
     * replaced by the person at their first sign-in in the browser (#322):
     * a password someone else has seen is theirs only once they replace it.
     */
    String createUser(String tenantId, String email, String firstName, String lastName, String password,
            boolean temporary);

    /**
     * Lift the "choose your own password" requirement from a login. Used when a
     * login becomes a digital worker's badge: a worker signs in by machine and
     * cannot answer a browser prompt, so its password is never temporary.
     */
    void clearPasswordChange(String tenantId, String userId);

    List<IdpRole> userRoles(String tenantId, String userId);

    void grant(String tenantId, String userId, String roleName);

    void revoke(String tenantId, String userId, String roleName);

    /**
     * GDPR erasure's identity half: disable the login and scrub the
     * profile's personal fields at the IdP. The account id remains (it
     * is the party reference the audit keeps); the person can no longer
     * authenticate and their name/email are gone from the realm.
     */
    void eraseUser(String tenantId, String userId);
}
