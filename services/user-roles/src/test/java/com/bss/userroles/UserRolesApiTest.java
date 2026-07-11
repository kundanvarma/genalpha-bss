package com.bss.userroles;

import com.bss.userroles.service.IdpAdminClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserRolesApiTest {

    private static final String BASE = "/tmf-api/rolesAndPermissionsManagement/v4";
    private static final String ISSUER_A = "https://idp.tenant-a.test/realms/bss";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IdpAdminClient idp;

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("roles:admin"));
    }

    private static RequestPostProcessor adminOf(String issuer) {
        return jwt().jwt(j -> j.issuer(issuer)).authorities(new SimpleGrantedAuthority("roles:admin"));
    }

    @Test
    void internalIdpRolesAreNeitherListedNorGrantable() throws Exception {
        given(idp.realmRoles("genalpha")).willReturn(List.of(
                Map.of("id", "r1", "name", "agent"),
                Map.of("id", "r2", "name", "offline_access"),
                Map.of("id", "r3", "name", "default-roles-bss")));

        mockMvc.perform(get(BASE + "/userRole").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("agent"));

        mockMvc.perform(post(BASE + "/permission").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user\": {\"id\": \"u1\"}, \"userRole\": {\"name\": \"offline_access\"}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void grantAndRevoke_addressTheCallersTenantOnly() throws Exception {
        mockMvc.perform(post(BASE + "/permission").with(adminOf(ISSUER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user\": {\"id\": \"u9\"}, \"userRole\": {\"name\": \"agent\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userRole.name").value("agent"));
        verify(idp).grant("tenant-a", "u9", "agent");

        String id = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("u9~agent".getBytes());
        mockMvc.perform(delete(BASE + "/permission/" + id).with(adminOf(ISSUER_A)))
                .andExpect(status().isNoContent());
        verify(idp).revoke("tenant-a", "u9", "agent");
    }

    @Test
    void requiresTheAdminAuthority() throws Exception {
        mockMvc.perform(get(BASE + "/userRole")
                        .with(jwt().authorities(new SimpleGrantedAuthority("agent"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/userRole"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void serviceAccountsAreHiddenFromUserLists() throws Exception {
        given(idp.users(any(), any())).willReturn(List.of(
                Map.of("id", "u1", "username", "agent-anna", "firstName", "Anna"),
                Map.of("id", "u2", "username", "service-account-bss-ordering")));
        mockMvc.perform(get(BASE + "/user").with(admin()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("agent-anna"));
    }
}
