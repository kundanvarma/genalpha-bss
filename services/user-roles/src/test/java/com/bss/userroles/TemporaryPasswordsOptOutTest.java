package com.bss.userroles;

import com.bss.userroles.service.IdpAdminClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** #322: the demo fleet's opt-out — the one place a handed-out password is not temporary. */
@SpringBootTest(properties = "bss.identity.temporary-passwords=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TemporaryPasswordsOptOutTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IdpAdminClient idp;

    @Test
    void withTheOptOutAHandedOutPasswordIsPermanent() throws Exception {
        given(idp.createUser(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .willReturn("u-suite");
        mockMvc.perform(post("/tmf-api/rolesAndPermissionsManagement/v4/user")
                        .with(jwt().authorities(new SimpleGrantedAuthority("roles:admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"suite@x.example\",\"givenName\":\"S\",\"familyName\":\"T\"}"))
                .andExpect(status().isCreated());
        verify(idp).createUser(eq("genalpha"), eq("suite@x.example"), anyString(), anyString(), anyString(), eq(false));
    }
}
