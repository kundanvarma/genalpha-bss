package com.bss.document;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Base64;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentApiTest {

    private static final String BASE = "/tmf-api/documentManagement/v4";
    private static final String SVG = "<svg xmlns='http://www.w3.org/2000/svg'/>";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor staff() {
        return jwt().authorities(
                new SimpleGrantedAuthority("document:read"),
                new SimpleGrantedAuthority("document:write"));
    }

    @Test
    void uploadedImageServesAnonymously_withItsContentType() throws Exception {
        MvcResult created = mockMvc.perform(post(BASE + "/document").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "logo", "category": "brand", "mimeType": "image/svg+xml",
                                 "content": "%s"}
                                """.formatted(Base64.getEncoder().encodeToString(SVG.getBytes()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachmentUrl").isNotEmpty())
                .andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        // The shop window: no token, correct type, cacheable, exact bytes.
        mockMvc.perform(get(BASE + "/document/" + id + "/content"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/svg+xml"))
                .andExpect(content().string(SVG));

        // Metadata stays behind identity; uploads reject non-image types.
        mockMvc.perform(get(BASE + "/document"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/document").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"x\", \"mimeType\": \"text/html\", \"content\": \"PGI+\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * A product manager exported a phone photo and the shop refused it, because
     * the export was AVIF — which every browser this BSS supports renders, and
     * which phones and design tools now produce by default. The console showed
     * them "HTTP 400" and nothing else, so the format was never the obvious
     * suspect. Both halves are pinned here: AVIF is taken, and a refusal still
     * NAMES the formats rather than just saying no.
     */
    @Test
    void avifIsAccepted_andARefusalNamesTheFormatsItTakes() throws Exception {
        mockMvc.perform(post(BASE + "/document").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "samsung galaxy s26 black", "category": "offering",
                                 "mimeType": "image/avif", "content": "%s"}
                                """.formatted(Base64.getEncoder().encodeToString("not-really-avif".getBytes()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachmentUrl").isNotEmpty());

        mockMvc.perform(post(BASE + "/document").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"x\", \"mimeType\": \"image/heic\", \"content\": \"PGI+\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> {
                    String said = result.getResolvedException() == null ? ""
                            : String.valueOf(result.getResolvedException().getMessage());
                    if (!said.contains("image/avif") || !said.contains("image/png")) {
                        throw new AssertionError("a refusal must name the formats it takes; it said: " + said);
                    }
                });
    }
}
