package com.bss.document;

import com.bss.document.dto.ContentProviderConfigRequest;
import com.bss.document.dto.ContentProviderConfigView;
import com.bss.document.dto.DocumentRequest;
import com.bss.document.dto.DocumentView;
import com.bss.document.dto.WebhookResult;
import com.bss.document.entity.ContentProviderConfig;
import com.bss.document.entity.StoredDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure Jackson: the bytes and the key order of document's wire records. */
class DtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private String write(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    private static StoredDocument stored() {
        StoredDocument d = new StoredDocument();
        d.setId("d-1");
        d.setHref("/tmf-api/documentManagement/v4/document/d-1");
        d.setName("Brand logo");
        d.setContentType("image/svg+xml");
        return d;
    }

    @Test
    void anAssetPublishesItsReadPathAndNeverItsBytes() throws Exception {
        assertThat(write(DocumentView.of(stored())))
                .isEqualTo("{\"id\":\"d-1\",\"href\":\"/tmf-api/documentManagement/v4/document/"
                        + "d-1\",\"name\":\"Brand logo\",\"mimeType\":\"image/svg+xml\","
                        + "\"attachmentUrl\":\"/tmf-api/documentManagement/v4/document/d-1"
                        + "/content\",\"@type\":\"Document\"}");
        StoredDocument full = stored();
        full.setCategory("brand");
        full.setDescription("the operator's mark");
        full.setLink("https://example.invalid/brand");
        full.setContent(new byte[] {1, 2, 3});
        assertThat(write(DocumentView.of(full)))
                .contains("\"name\":\"Brand logo\",\"category\":\"brand\",\"mimeType\"")
                .contains("\"description\":\"the operator's mark\","
                        + "\"link\":\"https://example.invalid/brand\",\"attachmentUrl\"")
                .doesNotContain("content\":[");
    }

    @Test
    void aBindingPublishesEveryReferenceAndNoSecret() throws Exception {
        ContentProviderConfig c = new ContentProviderConfig();
        c.setTenantId("genalpha");
        c.setProvider("sanity");
        c.setProjectId("p-1");
        c.setDataset("production");
        c.setSecretRef("SANITY_TOKEN");
        c.setWebhookSecretRef("SANITY_HOOK");
        c.setDirectUrl(true);
        assertThat(write(ContentProviderConfigView.of(c)))
                .isEqualTo("{\"tenantId\":\"genalpha\",\"provider\":\"sanity\","
                        + "\"projectId\":\"p-1\",\"dataset\":\"production\","
                        + "\"secretRef\":\"SANITY_TOKEN\",\"webhookSecretRef\":\"SANITY_HOOK\","
                        + "\"directUrl\":true,\"@type\":\"ContentProviderConfig\"}");
        ContentProviderConfig bare = new ContentProviderConfig();
        bare.setTenantId("genalpha");
        bare.setProvider("http");
        bare.setBaseUrl("https://example.invalid/dam");
        assertThat(write(ContentProviderConfigView.of(bare)))
                .isEqualTo("{\"tenantId\":\"genalpha\",\"provider\":\"http\","
                        + "\"baseUrl\":\"https://example.invalid/dam\",\"directUrl\":false,"
                        + "\"@type\":\"ContentProviderConfig\"}");
    }

    @Test
    void onlyARealJsonTrueTurnsDirectUrlOn() throws Exception {
        assertThat(mapper.readValue("{\"directUrl\":true}", ContentProviderConfigRequest.class)
                .directUrlOrFalse()).isTrue();
        // the map path compared Boolean.TRUE against the parsed value
        assertThat(mapper.readValue("{\"directUrl\":\"true\"}", ContentProviderConfigRequest.class)
                .directUrlOrFalse()).isFalse();
        assertThat(mapper.readValue("{\"directUrl\":1}", ContentProviderConfigRequest.class)
                .directUrlOrFalse()).isFalse();
        assertThat(mapper.readValue("{}", ContentProviderConfigRequest.class)
                .directUrlOrFalse()).isFalse();
    }

    @Test
    void aConfigStringIsStoredVerbatimAndAConfigObjectIsSerialised() throws Exception {
        assertThat(mapper.readValue("{\"config\":\"{\\\"raw\\\":true}\"}",
                ContentProviderConfigRequest.class).configJson()).isEqualTo("{\"raw\":true}");
        assertThat(mapper.readValue("{\"config\":{\"cdn\":\"fast\",\"a\":1}}",
                ContentProviderConfigRequest.class).configJson())
                .isEqualTo("{\"cdn\":\"fast\",\"a\":1}");
        assertThat(mapper.readValue("{\"config\":null}", ContentProviderConfigRequest.class)
                .configJson()).isNull();
        assertThat(mapper.readValue("{}", ContentProviderConfigRequest.class).configJson())
                .isNull();
    }

    @Test
    void aWebhookAnswersWhatItTouched() throws Exception {
        assertThat(write(new WebhookResult("genalpha", "delete", "asset-1", 3)))
                .isEqualTo("{\"tenantId\":\"genalpha\",\"operation\":\"delete\","
                        + "\"assetId\":\"asset-1\",\"matched\":3}");
    }

    @Test
    void anUploadBodyCannotCarryAColumnItNeverHad() throws Exception {
        DocumentRequest req = mapper.readValue(
                "{\"name\":\"Logo\",\"mimeType\":\"image/png\",\"content\":\"AAA=\","
                        + "\"tenantId\":\"other\",\"storageKey\":\"ref:sanity:x\"}",
                DocumentRequest.class);
        assertThat(req.name()).isEqualTo("Logo");
        assertThat(req.category()).isNull();
        assertThat(mapper.readValue("{}", DocumentRequest.class).content()).isNull();
    }
}
