package com.bss.ontology;

import com.bss.ontology.controller.McpController;
import com.bss.ontology.dto.Check;
import com.bss.ontology.registry.Registry;
import com.bss.ontology.security.ClaimAuthoritiesConverter;
import com.bss.ontology.security.ProtectedResourceMetadata;
import com.bss.ontology.security.SecurityConfig;
import com.bss.ontology.security.TenantRegistry;
import com.bss.ontology.security.TenantScope;
import com.bss.ontology.service.ActionCheckService;
import com.bss.ontology.service.ActionExecuteService;
import com.bss.ontology.service.ContextService;
import com.bss.ontology.service.ExplainService;
import com.bss.ontology.service.McpApps;
import com.bss.ontology.service.McpResources;
import com.bss.ontology.service.RecommendationService;
import com.bss.ontology.service.Resolver;
import com.bss.ontology.service.UpgradeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The MCP door at protocol 2026-07-28, dual-era: a modern client is served per
 * request from its {@code _meta} with no handshake, a 2025-06-18 client still
 * opens with initialize; the routing headers must agree with the body; the
 * ontology's concepts are readable resources; pickBenefit's dry run carries an
 * MCP Apps confirm card; and an agent without a token can learn where to get one.
 */
@WebMvcTest(controllers = McpController.class, properties = {
        "bss.tenants.default-tenant=genalpha",
        "bss.tenants.registry[0].id=genalpha",
        "bss.tenants.registry[0].issuer=https://idp.genalpha.test/realms/bss",
        "bss.tenants.registry[1].id=nova",
        "bss.tenants.registry[1].issuer=https://idp.nova.test/realms/nova"})
@Import({SecurityConfig.class, TenantScope.class, TenantRegistry.class, ClaimAuthoritiesConverter.class,
        ProtectedResourceMetadata.class, Registry.class, McpResources.class, McpApps.class, McpServerTest.Props.class})
class McpServerTest {

    private static final String MCP = "/ontology/v1/mcp";
    private static final String MODERN = "2026-07-28";

    @TestConfiguration
    @EnableConfigurationProperties
    static class Props {
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private ActionCheckService checks;
    @MockitoBean
    private ActionExecuteService executes;
    @MockitoBean
    private UpgradeService upgrades;
    @MockitoBean
    private ExplainService explain;
    @MockitoBean
    private ContextService context;
    @MockitoBean
    private RecommendationService recommendations;

    private static RequestPostProcessor person() {
        return jwt().jwt(j -> j.subject("cust-1").issuer("https://idp.genalpha.test/realms/bss"))
                .authorities(new SimpleGrantedAuthority("customer"));
    }

    private static String legacy(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    /** A 2026-07-28 request: its version and capabilities in _meta, nothing remembered from before. */
    private static String modern(String method, String params) {
        String meta = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"" + MODERN + "\","
                + "\"io.modelcontextprotocol/clientInfo\":{\"name\":\"test\",\"version\":\"1\"},"
                + "\"io.modelcontextprotocol/clientCapabilities\":{}}";
        String p = params.equals("{}") ? "{" + meta + "}" : params.substring(0, params.length() - 1) + "," + meta + "}";
        return legacy(method, p);
    }

    private ResultActions rpc(String body, String... headers) throws Exception {
        MockHttpServletRequestBuilder b = post(MCP).with(person()).contentType(MediaType.APPLICATION_JSON).content(body);
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return mvc.perform(b);
    }

    // ---------------------------------------------------------------- negotiation

    @Test
    void aLegacyClientStillInitializesAt2025_06_18() throws Exception {
        rpc(legacy("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"old\",\"version\":\"1\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-06-18"))
                .andExpect(jsonPath("$.result.serverInfo.name").value("genalpha-ontology"))
                .andExpect(jsonPath("$.result.capabilities.resources").exists())
                .andExpect(jsonPath("$.result.capabilities.extensions['io.modelcontextprotocol/ui'].mimeTypes[0]").value(McpApps.MIME))
                .andExpect(jsonPath("$.result.resultType").doesNotExist());
    }

    @Test
    void initializeAnswersTheClientsVersionWhenSpoken_elseTheNewestAHandshakeLeadsTo() throws Exception {
        rpc(legacy("initialize", "{\"protocolVersion\":\"2026-07-28\",\"capabilities\":{}}"))
                .andExpect(jsonPath("$.result.protocolVersion").value(MODERN));
        rpc(legacy("initialize", "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{}}"))
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-06-18"));
    }

    @Test
    void aModernClientIsServedStatelessly_noInitialize_discoverListsBothVersions() throws Exception {
        rpc(modern("server/discover", "{}"), "MCP-Protocol-Version", MODERN, "Mcp-Method", "server/discover")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.resultType").value("complete"))
                .andExpect(jsonPath("$.result.supportedVersions[0]").value(MODERN))
                .andExpect(jsonPath("$.result.supportedVersions[1]").value("2025-06-18"))
                .andExpect(jsonPath("$.result._meta['io.modelcontextprotocol/serverInfo'].name").value("genalpha-ontology"));
        rpc(modern("tools/list", "{}"), "MCP-Protocol-Version", MODERN, "Mcp-Method", "tools/list")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.resultType").value("complete"))
                .andExpect(jsonPath("$.result.tools[*].name", hasItem("list_actions")))
                .andExpect(header().doesNotExist("Mcp-Session-Id"));
    }

    @Test
    void anUnsupportedVersionIs400WithTheVersionsWeSpeak() throws Exception {
        String body = modern("tools/list", "{}").replace(MODERN, "1900-01-01");
        rpc(body, "MCP-Protocol-Version", "1900-01-01", "Mcp-Method", "tools/list")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32022))
                .andExpect(jsonPath("$.error.data.supported[0]").value(MODERN))
                .andExpect(jsonPath("$.error.data.requested").value("1900-01-01"));
    }

    @Test
    void aModernRequestWithoutItsCapabilitiesIsMalformed() throws Exception {
        String body = legacy("tools/list", "{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"" + MODERN + "\"}}");
        rpc(body, "MCP-Protocol-Version", MODERN, "Mcp-Method", "tools/list")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602));
    }

    // ---------------------------------------------------------------- routing headers

    @Test
    void aMethodHeaderThatDisagreesWithTheBodyIsRefused() throws Exception {
        // the gateway would route on "tools/list"; the body asks for something else
        rpc(legacy("tools/call", "{\"name\":\"list_actions\",\"arguments\":{}}"), "Mcp-Method", "tools/list")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32020))
                .andExpect(jsonPath("$.error.message", containsString("Mcp-Method")));
    }

    @Test
    void aNameHeaderThatDisagreesWithTheBodyIsRefused_aBase64OneThatAgreesIsServed() throws Exception {
        String call = modern("tools/call", "{\"name\":\"list_actions\",\"arguments\":{}}");
        rpc(call, "MCP-Protocol-Version", MODERN, "Mcp-Method", "tools/call", "Mcp-Name", "explain")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32020))
                .andExpect(jsonPath("$.error.message", containsString("Mcp-Name")));
        String encoded = "=?base64?" + Base64.getEncoder().encodeToString("list_actions".getBytes(StandardCharsets.UTF_8)) + "?=";
        rpc(call, "MCP-Protocol-Version", MODERN, "Mcp-Method", "tools/call", "Mcp-Name", encoded)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false));
    }

    @Test
    void aModernRequestMustCarryItsRoutingHeaders() throws Exception {
        rpc(modern("tools/list", "{}"), "MCP-Protocol-Version", MODERN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32020));
        rpc(modern("tools/call", "{\"name\":\"list_actions\",\"arguments\":{}}"), "MCP-Protocol-Version", MODERN, "Mcp-Method", "tools/call")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32020));
        rpc(modern("tools/list", "{}"), "MCP-Protocol-Version", "2025-06-18", "Mcp-Method", "tools/list")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32020));
    }

    // ---------------------------------------------------------------- resources

    @Test
    void theConceptsAreResources_andReadAsTheirJson() throws Exception {
        rpc(legacy("resources/list", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.resources[*].uri", hasItem("ontology://concept/Customer")))
                .andExpect(jsonPath("$.result.resources[*].uri", hasItem("ontology://concept/Subscription")))
                .andExpect(jsonPath("$.result.resources[*].uri", hasItem(McpApps.PICK_BENEFIT_CARD)));
        String read = rpc(modern("resources/read", "{\"uri\":\"ontology://concept/Customer\"}"),
                "MCP-Protocol-Version", MODERN, "Mcp-Method", "resources/read", "Mcp-Name", "ontology://concept/Customer")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.contents[0].mimeType").value("application/json"))
                .andReturn().getResponse().getContentAsString();
        String text = json.readTree(read).path("result").path("contents").path(0).path("text").asString();
        org.assertj.core.api.Assertions.assertThat(json.readTree(text).path("concept").asString()).isEqualTo("Customer");
        rpc(legacy("resources/read", "{\"uri\":\"ontology://concept/Nonsense\"}"))
                .andExpect(jsonPath("$.error.code").value(-32602));
    }

    // ---------------------------------------------------------------- MCP Apps

    @Test
    void thePickBenefitCardIsAUiResource_selfContained_andBuildsNoMarkupFromData() throws Exception {
        String read = rpc(legacy("resources/read", "{\"uri\":\"" + McpApps.PICK_BENEFIT_CARD + "\"}"))
                .andExpect(jsonPath("$.result.contents[0].mimeType").value("text/html;profile=mcp-app"))
                .andExpect(jsonPath("$.result.contents[0]._meta.ui.prefersBorder").value(true))
                .andExpect(jsonPath("$.result.contents[0]._meta.ui.csp.connectDomains").isEmpty())
                .andReturn().getResponse().getContentAsString();
        String html = json.readTree(read).path("result").path("contents").path(0).path("text").asString();
        org.assertj.core.api.Assertions.assertThat(html)
                .contains("ui/initialize", "ui/notifications/tool-result", "'tools/call'", "Confirm", "textContent")
                .doesNotContain("<script src", "innerHTML", "<link", "http://", "https://", "eval(");
    }

    @Test
    void theDryRunOfPickBenefitLinksTheCard_theExecutingToolDoesNot() throws Exception {
        rpc(legacy("tools/list", "{}"))
                .andExpect(jsonPath("$.result.tools[?(@.name=='check_pick_benefit')]._meta.ui.resourceUri", hasItem(McpApps.PICK_BENEFIT_CARD)))
                .andExpect(jsonPath("$.result.tools[?(@.name=='pick_benefit')].name", hasItem("pick_benefit")))
                .andExpect(jsonPath("$.result.tools[?(@.name=='pick_benefit')]._meta").doesNotExist());
    }

    @Test
    void theDryRunResultCarriesTheCard_byNameAndCostInChoices() throws Exception {
        Resolver.Resolved resolved = new Resolver.Resolved();
        resolved.objects.put("benefitOffering", json.readTree("{\"id\":\"po-stream\",\"name\":\"StreamCo Standard\",\"choiceCost\":1}"));
        when(checks.check(any(), any(), any())).thenReturn(new Check(true, null, List.of(), null, null, resolved));
        rpc(legacy("tools/call", "{\"name\":\"check_pick_benefit\",\"arguments\":{\"customerId\":\"cust-1\",\"benefitOfferingId\":\"po-stream\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result._meta['bss.genalpha/confirmCard'].benefitName").value("StreamCo Standard"))
                .andExpect(jsonPath("$.result._meta['bss.genalpha/confirmCard'].choiceCost").value(1))
                .andExpect(jsonPath("$.result._meta['bss.genalpha/confirmCard'].confirmTool").value("pick_benefit"))
                .andExpect(jsonPath("$.result._meta['bss.genalpha/confirmCard'].arguments.benefitOfferingId").value("po-stream"))
                .andExpect(jsonPath("$.result._meta['bss.genalpha/confirmCard'].allowed").value(true));
    }

    // ---------------------------------------------------------------- RFC 9728

    @Test
    void anAgentWithoutATokenLearnsTheTenantsIssuer() throws Exception {
        mvc.perform(get("/.well-known/oauth-protected-resource" + MCP)
                        .header("X-Tenant-Id", "nova").header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "shop.nova.test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value("https://shop.nova.test" + MCP))
                .andExpect(jsonPath("$.authorization_servers[0]").value("https://idp.nova.test/realms/nova"))
                .andExpect(jsonPath("$.authorization_servers.length()").value(1))
                .andExpect(jsonPath("$.bearer_methods_supported[0]").value("header"));
        mvc.perform(get("/.well-known/oauth-protected-resource"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorization_servers[0]").value("https://idp.genalpha.test/realms/bss"));
        // an unknown tenant id in the header falls back to the default, like every anonymous read
        mvc.perform(get("/.well-known/oauth-protected-resource").header("X-Tenant-Id", "nobody"))
                .andExpect(jsonPath("$.authorization_servers[0]").value("https://idp.genalpha.test/realms/bss"));
    }

    @Test
    void theMcpDoorItselfStaysShut_andSaysWhereTheMetadataIs() throws Exception {
        // behind the gateway the pod's own host is not where the client is: the pointer names the public origin
        mvc.perform(post(MCP).contentType(MediaType.APPLICATION_JSON).content(legacy("tools/list", "{}"))
                        .header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "shop.nova.test"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        containsString("resource_metadata=\"https://shop.nova.test/.well-known/oauth-protected-resource" + MCP + "\"")));
    }
}
