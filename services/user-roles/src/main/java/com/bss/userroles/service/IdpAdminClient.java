package com.bss.userroles.service;

import java.util.List;
import java.util.Map;

/**
 * The slice of an IdP's admin API this component needs. One implementation
 * per IdP family; dev ships Keycloak. The tenant is decided by the CALLER's
 * verified issuer — a tenant admin can only ever manage their own realm.
 */
public interface IdpAdminClient {

    /** A re-minted realm invalidates cached machine tokens; IdPs without
     * a cache simply ignore this. */
    default void evictTokens(String tenantId) {
    }

    List<Map<String, Object>> realmRoles(String tenantId);

    List<Map<String, Object>> users(String tenantId, String username);

    /**
     * Create a login in the tenant's realm and return its IdP-assigned id
     * (the future token subject). The password is set non-temporary so the
     * person can sign in straight away through any channel.
     */
    String createUser(String tenantId, String email, String firstName, String lastName, String password);

    List<Map<String, Object>> userRoles(String tenantId, String userId);

    void grant(String tenantId, String userId, String roleName);

    void revoke(String tenantId, String userId, String roleName);
}
