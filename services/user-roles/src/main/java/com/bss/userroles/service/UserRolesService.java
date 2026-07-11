package com.bss.userroles.service;

import com.bss.userroles.exception.BadRequestException;
import com.bss.userroles.security.TenantScope;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TMF672 over the tenant's own IdP. Internal machinery roles are hidden;
 * a permission id encodes (user, role) so grants are addressable REST
 * resources. Everything runs against the CALLER's tenant — the issuer
 * decides which realm gets managed, so cross-tenant administration is
 * structurally impossible.
 */
@Service
public class UserRolesService {

    /** IdP plumbing, not business roles: never listed, never grantable here. */
    private static final Set<String> INTERNAL_ROLES = Set.of(
            "offline_access", "uma_authorization");

    private final IdpAdminClient idp;
    private final TenantScope tenantScope;

    public UserRolesService(IdpAdminClient idp, TenantScope tenantScope) {
        this.idp = idp;
        this.tenantScope = tenantScope;
    }

    public List<Map<String, Object>> roles() {
        return idp.realmRoles(tenantScope.currentTenantId()).stream()
                .filter(r -> !isInternal(String.valueOf(r.get("name"))))
                .map(r -> roleMap(String.valueOf(r.get("name")), r.get("description")))
                .toList();
    }

    public List<Map<String, Object>> users(String username) {
        return idp.users(tenantScope.currentTenantId(), username).stream()
                .filter(u -> !String.valueOf(u.get("username")).startsWith("service-account-"))
                .map(u -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", u.get("id"));
                    map.put("username", u.get("username"));
                    if (u.get("email") != null) map.put("email", u.get("email"));
                    if (u.get("firstName") != null) map.put("givenName", u.get("firstName"));
                    if (u.get("lastName") != null) map.put("familyName", u.get("lastName"));
                    map.put("@type", "User");
                    return map;
                }).toList();
    }

    public List<Map<String, Object>> permissionsOf(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BadRequestException("userId is required");
        }
        return idp.userRoles(tenantScope.currentTenantId(), userId).stream()
                .filter(r -> !isInternal(String.valueOf(r.get("name"))))
                .map(r -> permissionMap(userId, String.valueOf(r.get("name"))))
                .toList();
    }

    public Map<String, Object> grant(Map<String, Object> dto) {
        String userId = refId(dto.get("user"));
        String roleName = refName(dto.get("userRole"));
        if (isInternal(roleName)) {
            throw new BadRequestException("role '" + roleName + "' is not grantable");
        }
        idp.grant(tenantScope.currentTenantId(), userId, roleName);
        return permissionMap(userId, roleName);
    }

    public void revoke(String permissionId) {
        String decoded = new String(Base64.getUrlDecoder().decode(permissionId));
        int at = decoded.indexOf('~');
        if (at < 0) {
            throw new BadRequestException("malformed permission id");
        }
        idp.revoke(tenantScope.currentTenantId(), decoded.substring(0, at), decoded.substring(at + 1));
    }

    private boolean isInternal(String name) {
        return INTERNAL_ROLES.contains(name) || name.startsWith("default-roles-");
    }

    private Map<String, Object> roleMap(String name, Object description) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        if (description != null) map.put("description", description);
        map.put("@type", "UserRole");
        return map;
    }

    private Map<String, Object> permissionMap(String userId, String roleName) {
        String id = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((userId + "~" + roleName).getBytes());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("user", Map.of("id", userId, "@referredType", "User"));
        map.put("userRole", Map.of("name", roleName, "@referredType", "UserRole"));
        map.put("@type", "Permission");
        return map;
    }

    private String refId(Object ref) {
        if (ref instanceof Map<?, ?> map && map.get("id") != null) {
            return String.valueOf(map.get("id"));
        }
        throw new BadRequestException("user.id is required");
    }

    private String refName(Object ref) {
        if (ref instanceof Map<?, ?> map && map.get("name") != null) {
            return String.valueOf(map.get("name"));
        }
        throw new BadRequestException("userRole.name is required");
    }
}
