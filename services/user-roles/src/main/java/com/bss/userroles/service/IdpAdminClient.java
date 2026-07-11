package com.bss.userroles.service;

import java.util.List;
import java.util.Map;

/**
 * The slice of an IdP's admin API this component needs. One implementation
 * per IdP family; dev ships Keycloak. The tenant is decided by the CALLER's
 * verified issuer — a tenant admin can only ever manage their own realm.
 */
public interface IdpAdminClient {

    List<Map<String, Object>> realmRoles(String tenantId);

    List<Map<String, Object>> users(String tenantId, String username);

    List<Map<String, Object>> userRoles(String tenantId, String userId);

    void grant(String tenantId, String userId, String roleName);

    void revoke(String tenantId, String userId, String roleName);
}
