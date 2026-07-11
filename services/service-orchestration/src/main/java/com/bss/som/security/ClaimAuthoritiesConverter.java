package com.bss.som.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Maps token claims to authorities without vendor assumptions. Identity providers
 * differ in where they carry permissions (scope, scp, realm_access.roles, ...), so
 * the claim paths are configuration, not code. Claim values are used verbatim as
 * authority names (e.g. "service:read"); space-delimited strings are split.
 */
@Component
public class ClaimAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private final List<String> claimPaths;

    public ClaimAuthoritiesConverter(
            @Value("${bss.security.authority-claims:scope,scp,realm_access.roles}") List<String> claimPaths) {
        this.claimPaths = claimPaths;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String path : claimPaths) {
            Object value = resolve(jwt.getClaims(), path);
            if (value instanceof String s) {
                for (String token : s.split(" ")) {
                    if (!token.isBlank()) {
                        authorities.add(new SimpleGrantedAuthority(token));
                    }
                }
            } else if (value instanceof Collection<?> values) {
                for (Object v : values) {
                    authorities.add(new SimpleGrantedAuthority(String.valueOf(v)));
                }
            }
        }
        return authorities;
    }

    private Object resolve(Map<String, Object> claims, String path) {
        Object current = claims;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }
}
