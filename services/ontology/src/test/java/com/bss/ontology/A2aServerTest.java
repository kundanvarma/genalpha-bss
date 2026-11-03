package com.bss.ontology;

import com.bss.ontology.client.OntologyProperties;
import com.bss.ontology.controller.A2aController;
import com.bss.ontology.controller.AgentCardController;
import com.bss.ontology.dto.Check;
import com.bss.ontology.dto.ExecuteReceipt;
import com.bss.ontology.registry.Registry;
import com.bss.ontology.security.AgentCardSigner;
import com.bss.ontology.security.ClaimAuthoritiesConverter;
import com.bss.ontology.security.ProtectedResourceMetadata;
import com.bss.ontology.security.SecurityConfig;
import com.bss.ontology.security.TenantRegistry;
import com.bss.ontology.security.TenantScope;
import com.bss.ontology.service.A2aSkills;
import com.bss.ontology.service.A2aTasks;
import com.bss.ontology.service.ActionCheckService;
import com.bss.ontology.service.ActionExecuteService;
import com.bss.ontology.service.AgentCardService;
import com.bss.ontology.service.Caller;
import com.bss.ontology.service.ExplainService;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.util.Base64URL;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The A2A 1.0 door (#426): a partner's agent reads the tenant's Agent Card without
 * a token, then delegates a task with one; every skill runs through the existing
 * check/execute services (mocked here — their own tests prove the governance), and
 * the task carries the receipt's decision id. The card names only what the door
 * serves, the tenant's own issuer, and verifies under the deployment's key.
 */
@WebMvcTest(controllers = {A2aController.class, AgentCardController.class}, properties = {
        "bss.tenants.default-tenant=genalpha",
        "bss.tenants.registry[0].id=genalpha",
        "bss.tenants.registry[0].issuer=https://idp.genalpha.test/realms/bss",
        "bss.tenants.registry[1].id=nova",
        "bss.tenants.registry[1].issuer=https://idp.nova.test/realms/nova",
        "ontology.a2a.execute-enabled=true",
        "ontology.a2a.signing-key-id=card-key-1"})
@Import({SecurityConfig.class, TenantScope.class, TenantRegistry.class, ClaimAuthoritiesConverter.class,
        ProtectedResourceMetadata.class, Registry.class, ExplainService.class, A2aSkills.class, A2aTasks.class,
        AgentCardService.class, AgentCardSigner.class, A2aServerTest.Props.class})
class A2aServerTest {

    private static final String A2A = "/ontology/v1/a2a";
    private static final String CARD = "/.well-known/agent-card.json";
    private static final KeyPair KEYS = ecKeys();

    @TestConfiguration
    @EnableConfigurationProperties(OntologyProperties.class)
    static class Props {
    }

    /** The deployment's key, as an operator would set ONTOLOGY_A2A_SIGNING_KEY — made up per run, never in the repo. */
    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry r) {
        r.add("ontology.a2a.signing-key", () -> Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded()));
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private Registry registry;
    @Autowired
    private ExplainService explain;

    @MockitoBean
    private ActionCheckService checks;
    @MockitoBean
    private ActionExecuteService executes;

    private static KeyPair ecKeys() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RequestPostProcessor person(String subject) {
        return jwt().jwt(j -> j.subject(subject).issuer("https://idp.genalpha.test/realms/bss"))
                .authorities(new SimpleGrantedAuthority("customer"), new SimpleGrantedAuthority("ordering:write"));
    }

    private static String rpc(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    private static String send(String skill, String inputs) {
        return rpc("SendMessage", "{\"message\":{\"messageId\":\"m-1\",\"role\":\"ROLE_USER\",\"parts\":["
                + "{\"text\":\"please check\"},{\"data\":{\"skill\":\"" + skill + "\",\"inputs\":" + inputs + "}}]}}");
    }

    private ResultActions call(String body, String subject) throws Exception {
        MockHttpServletRequestBuilder b = post(A2A).with(person(subject)).header("A2A-Version", "1.0")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(b);
    }

    // ---------------------------------------------------------------- the card

    @Test
    void theCardNamesTheEndpoint_theTenantsIssuer_andOnlyTheSkillsServed() throws Exception {
        mvc.perform(get(CARD).header("X-Tenant-Id", "nova").header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "shop.nova.test"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=300")))
                .andExpect(jsonPath("$.supportedInterfaces.length()").value(1))
                .andExpect(jsonPath("$.supportedInterfaces[0].url").value("https://shop.nova.test" + A2A))
                .andExpect(jsonPath("$.supportedInterfaces[0].protocolBinding").value("JSONRPC"))
                .andExpect(jsonPath("$.supportedInterfaces[0].protocolVersion").value("1.0"))
                .andExpect(jsonPath("$.version").exists())
                .andExpect(jsonPath("$.capabilities.streaming").value(false))
                .andExpect(jsonPath("$.capabilities.pushNotifications").value(false))
                .andExpect(jsonPath("$.defaultInputModes[0]").value("application/json"))
                .andExpect(jsonPath("$.skills[*].id", containsInAnyOrder("check-pick-benefit", "explain-action", "pick-benefit")))
                .andExpect(jsonPath("$.securitySchemes.oauth2.oauth2SecurityScheme.flows.authorizationCode.authorizationUrl")
                        .value("https://idp.nova.test/realms/nova/protocol/openid-connect/auth"))
                .andExpect(jsonPath("$.securitySchemes.oauth2.oauth2SecurityScheme.flows.authorizationCode.tokenUrl")
                        .value("https://idp.nova.test/realms/nova/protocol/openid-connect/token"))
                .andExpect(jsonPath("$.securitySchemes.oauth2.oauth2SecurityScheme.flows.authorizationCode.scopes").isMap())
                .andExpect(jsonPath("$.securitySchemes.oauth2.oauth2SecurityScheme.oauth2MetadataUrl")
                        .value("https://idp.nova.test/realms/nova/.well-known/openid-configuration"))
                .andExpect(jsonPath("$.securityRequirements[0].schemes.oauth2").isMap());
    }

    @Test
    void anotherTenantsCardNamesThatTenantsIssuer_andNoHeaderMeansTheDefault() throws Exception {
        mvc.perform(get(CARD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.securitySchemes.oauth2.oauth2SecurityScheme.flows.authorizationCode.tokenUrl",
                        startsWith("https://idp.genalpha.test/realms/bss/")));
        mvc.perform(get(CARD).header("X-Tenant-Id", "nobody"))
                .andExpect(jsonPath("$.securitySchemes.oauth2.oauth2SecurityScheme.oauth2MetadataUrl",
                        startsWith("https://idp.genalpha.test/realms/bss/")));
    }

    @Test
    void withExecutingOff_theCardAndTheDoorOfferOnlyTheDryRunAndExplain() {
        OntologyProperties off = new OntologyProperties();
        A2aSkills skills = new A2aSkills(registry, checks, executes, explain, off);
        assertThat(skills.offered("genalpha")).extracting(s -> s.id()).containsExactly("check-pick-benefit", "explain-action");
        Caller caller = new Caller("t", "cust-1", java.util.Set.of("ordering:write"), "web", "genalpha", "partner-bot");
        A2aSkills.Result r = skills.run("pick-benefit", json.createObjectNode().put("customerId", "c").put("benefitOfferingId", "b"), caller);
        assertThat(r.state()).isEqualTo("TASK_STATE_REJECTED");
        verify(executes, never()).execute(any(), any(), any());
    }

    @Test
    void theCardIsSignedOverItsCanonicalForm_andATamperedCardDoesNotVerify() throws Exception {
        String body = mvc.perform(get(CARD).header("X-Tenant-Id", "nova"))
                .andExpect(jsonPath("$.signatures.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        ObjectNode card = (ObjectNode) json.readTree(body);
        JsonNode sig = card.get("signatures").get(0);
        JWSHeader header = JWSHeader.parse(new Base64URL(sig.path("protected").asString()));
        assertThat(header.getAlgorithm().getName()).isEqualTo("ES256");
        assertThat(header.getKeyID()).isEqualTo("card-key-1");
        assertThat(header.getType().getType()).isEqualTo("JOSE");
        card.remove("signatures");
        ECDSAVerifier verifier = new ECDSAVerifier((ECPublicKey) KEYS.getPublic());
        assertThat(verifier.verify(header, input(sig, card), new Base64URL(sig.path("signature").asString()))).isTrue();
        card.put("name", "Someone Else's Agent");
        assertThat(verifier.verify(header, input(sig, card), new Base64URL(sig.path("signature").asString()))).isFalse();
    }

    private static byte[] input(JsonNode sig, JsonNode card) {
        String payload = Base64URL.encode(AgentCardSigner.canonical(card).getBytes(StandardCharsets.UTF_8)).toString();
        return (sig.path("protected").asString() + "." + payload).getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void canonicalFormSortsKeys_dropsWhitespace_andEscapesLikeJcs() throws Exception {
        JsonNode n = json.readTree("{\"b\":[true,1,null],\"a\":\"x\\n\\u0001é\",\"A\":{}}");
        assertThat(AgentCardSigner.canonical(n)).isEqualTo("{\"A\":{},\"a\":\"x\\n\\u0001é\",\"b\":[true,1,null]}");
    }

    // ---------------------------------------------------------------- the door

    @Test
    void withoutATokenTheDoorIsShut_theCardIsNot() throws Exception {
        mvc.perform(post(A2A).header("A2A-Version", "1.0").contentType(MediaType.APPLICATION_JSON).content(send("explain-action", "{}")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(CARD)).andExpect(status().isOk());
    }

    @Test
    void aDryRunCompletes_throughTheCheckService_namingTheAgentAndSayingNoReceiptWasWritten() throws Exception {
        when(checks.check(any(), any(), any())).thenReturn(new Check(false, "the benefit must be on offer", List.of(), null, null, null));
        call(send("check-pick-benefit", "{\"customerId\":\"cust-1\",\"benefitOfferingId\":\"po-x\"}"), "cust-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.result.task.id").isString())
                .andExpect(jsonPath("$.result.task.status.state").value("TASK_STATE_COMPLETED"))
                .andExpect(jsonPath("$.result.task.status.message.role").value("ROLE_AGENT"))
                .andExpect(jsonPath("$.result.task.status.message.parts[0].text", containsString("the benefit must be on offer")))
                .andExpect(jsonPath("$.result.task.artifacts[0].parts[0].data.allowed").value(false))
                .andExpect(jsonPath("$.result.task.metadata.action").value("pickBenefit"))
                .andExpect(jsonPath("$.result.task.metadata.dryRun").value(true))
                .andExpect(jsonPath("$.result.task.metadata.receipt", startsWith("none")));
        ArgumentCaptor<Caller> who = ArgumentCaptor.forClass(Caller.class);
        verify(checks).check(any(), any(), who.capture());
        assertThat(who.getValue().agent()).isEqualTo("external-a2a");
        assertThat(who.getValue().subject()).isEqualTo("cust-1");
        assertThat(who.getValue().tenant()).isEqualTo("genalpha");
    }

    @Test
    void anExecutedTaskCarriesTheReceiptsDecisionId_andGetTaskReturnsIt_toItsOwnerOnly() throws Exception {
        Check ok = new Check(true, null, List.of(), null, null, null);
        when(executes.execute(any(), any(), any())).thenReturn(new ActionExecuteService.Outcome(true, 200,
                new ExecuteReceipt("pickBenefit", ok, "the caller", null, true, null, null, "ontology-d-42", null, null, null,
                        null, null, "Picked StreamCo Standard.")));
        String sent = call(send("pick-benefit", "{\"customerId\":\"cust-1\",\"benefitOfferingId\":\"po-stream\"}"), "cust-1")
                .andExpect(jsonPath("$.result.task.status.state").value("TASK_STATE_COMPLETED"))
                .andExpect(jsonPath("$.result.task.metadata.decisionId").value("ontology-d-42"))
                .andExpect(jsonPath("$.result.task.metadata.dryRun").value(false))
                .andExpect(jsonPath("$.result.task.metadata.receipt", startsWith("written")))
                .andReturn().getResponse().getContentAsString();
        String taskId = json.readTree(sent).path("result").path("task").path("id").asString();
        call(rpc("GetTask", "{\"id\":\"" + taskId + "\"}"), "cust-1")
                .andExpect(jsonPath("$.result.id").value(taskId))
                .andExpect(jsonPath("$.result.status.state").value("TASK_STATE_COMPLETED"))
                .andExpect(jsonPath("$.result.metadata.decisionId").value("ontology-d-42"));
        // another person's token cannot read it, and cannot tell it exists
        call(rpc("GetTask", "{\"id\":\"" + taskId + "\"}"), "someone-else")
                .andExpect(jsonPath("$.error.code").value(-32001));
        // a finished task cannot be cancelled
        call(rpc("CancelTask", "{\"id\":\"" + taskId + "\"}"), "cust-1")
                .andExpect(jsonPath("$.error.code").value(-32002));
    }

    @Test
    void aGovernedRefusalIsARejectedTask_inTheActionsOwnWords() throws Exception {
        Check no = new Check(false, "the benefit must be on offer", List.of(), null, null, null);
        when(executes.execute(any(), any(), any())).thenReturn(new ActionExecuteService.Outcome(false, 422,
                new ExecuteReceipt("pickBenefit", no, null, null, false, null, null, null, null, "the benefit must be on offer",
                        null, null, null, "Refused: the benefit must be on offer.")));
        call(send("pick-benefit", "{\"customerId\":\"cust-1\",\"benefitOfferingId\":\"po-gone\"}"), "cust-1")
                .andExpect(jsonPath("$.result.task.status.state").value("TASK_STATE_REJECTED"))
                .andExpect(jsonPath("$.result.task.status.message.parts[0].text", containsString("must be on offer")));
    }

    @Test
    void anUnknownSkillIsRejected_andNothingIsCheckedOrExecuted() throws Exception {
        call(send("transfer-all-the-money", "{}"), "cust-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.task.status.state").value("TASK_STATE_REJECTED"))
                .andExpect(jsonPath("$.result.task.status.message.parts[0].text", containsString("no skill")));
        verify(checks, never()).check(any(), any(), any());
        verify(executes, never()).execute(any(), any(), any());
    }

    @Test
    void aMissingInputAsksForIt() throws Exception {
        call(send("check-pick-benefit", "{\"customerId\":\"cust-1\"}"), "cust-1")
                .andExpect(jsonPath("$.result.task.status.state").value("TASK_STATE_INPUT_REQUIRED"))
                .andExpect(jsonPath("$.result.task.status.message.parts[0].text", containsString("benefitOfferingId")));
        verify(checks, never()).check(any(), any(), any());
    }

    @Test
    void explainAnswersFromTheRegistry() throws Exception {
        call(send("explain-action", "{\"action\":\"pickBenefit\"}"), "cust-1")
                .andExpect(jsonPath("$.result.task.status.state").value("TASK_STATE_COMPLETED"))
                .andExpect(jsonPath("$.result.task.artifacts[0].parts[0].data.name").value("pickBenefit"));
    }

    @Test
    void theProtocolVersionIsEnforced_and0_3MethodNamesAreNotSpoken() throws Exception {
        mvc.perform(post(A2A).with(person("cust-1")).contentType(MediaType.APPLICATION_JSON).content(send("explain-action", "{}")))
                .andExpect(jsonPath("$.error.code").value(-32009))
                .andExpect(jsonPath("$.error.data[0].metadata.requested").value("0.3"));
        call(rpc("message/send", "{}"), "cust-1").andExpect(jsonPath("$.error.code").value(-32009));
        call(rpc("SendStreamingMessage", "{}"), "cust-1").andExpect(jsonPath("$.error.code").value(-32004));
        call(rpc("NoSuchThing", "{}"), "cust-1").andExpect(jsonPath("$.error.code").value(-32601));
    }

    @Test
    void theTenantComesFromTheToken_neverFromTheRequest() throws Exception {
        call(rpc("SendMessage", "{\"tenant\":\"nova\",\"message\":{\"messageId\":\"m\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"x\"}]}}"), "cust-1")
                .andExpect(jsonPath("$.error.code").value(-32602));
    }

    @Test
    void aMalformedMessageIsInvalidParams() throws Exception {
        List<String> bad = new ArrayList<>(List.of(
                rpc("SendMessage", "{}"),
                rpc("SendMessage", "{\"message\":{\"messageId\":\"m\",\"role\":\"ROLE_USER\",\"parts\":[]}}"),
                rpc("SendMessage", "{\"message\":{\"messageId\":\"m\",\"role\":\"ROLE_AGENT\",\"parts\":[{\"text\":\"x\"}]}}")));
        for (String b : bad) {
            call(b, "cust-1").andExpect(jsonPath("$.error.code").value(-32602));
        }
    }
}
