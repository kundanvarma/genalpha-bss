package com.bss.userroles.service;

import com.bss.userroles.dto.BrandPatch;
import com.bss.userroles.exception.BadRequestException;
import com.bss.userroles.security.TenantFileRefresher;
import com.bss.userroles.security.TenantRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * tenants.yml is one file describing every operator: issuers, JWKS URIs,
 * machine credentials, seam URLs. Operator-supplied text is written into it,
 * and a hosted operator's own marketing team can reach the brand fields
 * through PATCH /onboarding/v1/myOperator. Written raw, a value carrying a
 * line break stops being a brand name and becomes registry lines of the
 * caller's choosing -- a storefront rename escalating into an edit of the
 * fleet's trust settings.
 *
 * These tests hold that shut from both write paths: the one that mints an
 * operator and the one a tenant can reach itself.
 */
class TenantRegistryInjectionTest {

    /* A registry with exactly the lines the two write paths rewrite, at the
     * indentation the real file uses -- the block regex depends on it, so the
     * closing delimiter sits at column zero to keep the leading spaces. */
    private static final String REGISTRY = """
bss:
  tenancy:
    tenants:
      - id: nova
        issuer: ${OIDC_ISSUER_URI_NOVA:http://localhost:8085/realms/nova}
        jwks-uri: http://keycloak:8080/realms/nova/protocol/openid-connect/certs
        token-uri: http://keycloak:8080/realms/nova/protocol/openid-connect/token
        machine-client-id: ${OIDC_CLIENT_ID:bss-assurance}
        machine-client-secret: ${OIDC_CLIENT_SECRET:assurance-secret}
        brand-name: Nova Mobil
        brand-color: "#B85C38"
        locale: "en"
        currency: EUR
        hosts: [shop.nova.localhost]
        agent-commerce: "off"
        ai-visibility: "search-only"
""";

    /** What an attacker wants in the file: their own line, on its own line. */
    private static final String INJECTED_ISSUER =
            "Acme\n        issuer: https://idp.attacker.example/realms/acme";

    @TempDir
    Path dir;

    private Path registry;
    private TenantOnboardingService onboarding;

    @BeforeEach
    void setUp() throws Exception {
        registry = dir.resolve("tenants.yml");
        Files.writeString(registry, REGISTRY);

        TenantRegistry tenants = mock(TenantRegistry.class);
        given(tenants.byId("acme")).willReturn(mock(TenantRegistry.TenantEntry.class));

        onboarding = new TenantOnboardingService(RestClient.builder(),
                "http://localhost:8085", "admin", "admin",
                "infra/keycloak/nova-realm.json", registry.toString(),
                "http://localhost:8081", "http://localhost:8113", "http://localhost:8083",
                "http://localhost:8097", "http://localhost:8086", "http://localhost:8104",
                "http://localhost:8084", "genalpha,nova",
                mock(IdpAdminClient.class),
                tenants, mock(TenantFileRefresher.class));
    }

    /* ---------- the path that mints an operator ---------- */

    @Test
    void anOrdinaryBrandNameIsWrittenQuoted() throws Exception {
        onboarding.appendTenantBlock("acme", "Acme Telecom", "en", "EUR", "#112233", "a-secret");

        assertThat(Files.readString(registry)).contains("brand-name: \"Acme Telecom\"");
    }

    @Test
    void aBrandNameCarryingARegistryLineIsRefused() {
        assertThatThrownBy(() -> onboarding.appendTenantBlock(
                "acme", INJECTED_ISSUER, "en", "EUR", "#112233", "a-secret"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("control characters or line breaks");
    }

    @Test
    void aRefusedNameLeavesTheRegistryUntouched() throws Exception {
        String before = Files.readString(registry);

        assertThatThrownBy(() -> onboarding.appendTenantBlock(
                "acme", INJECTED_ISSUER, "en", "EUR", "#112233", "a-secret"))
                .isInstanceOf(BadRequestException.class);

        assertThat(Files.readString(registry)).isEqualTo(before);
        assertThat(Files.readString(registry)).doesNotContain("idp.attacker.example");
    }

    @Test
    void currencyAndColourMustLookLikeThemselves() {
        assertThatThrownBy(() -> onboarding.appendTenantBlock(
                "acme", "Acme", "en", "EUR\n        issuer: x", "#112233", "a-secret"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> onboarding.appendTenantBlock(
                "acme", "Acme", "en", "EUR", "red", "a-secret"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> onboarding.appendTenantBlock(
                "acme", "Acme", "en\n        issuer: x", "EUR", "#112233", "a-secret"))
                .isInstanceOf(BadRequestException.class);
    }

    /* ---------- the path a tenant's own team can reach ---------- */

    @Test
    void aTenantCannotRenameItsWayIntoTheRegistry() throws Exception {
        onboarding.appendTenantBlock("acme", "Acme Telecom", "en", "EUR", "#112233", "a-secret");
        String before = Files.readString(registry);

        assertThatThrownBy(() -> onboarding.mutateBrand("acme",
                new BrandPatch(INJECTED_ISSUER, null, null)))
                .isInstanceOf(BadRequestException.class);

        assertThat(Files.readString(registry)).isEqualTo(before);
    }

    @Test
    void aTaglineCannotCarryARegistryLineEither() throws Exception {
        onboarding.appendTenantBlock("acme", "Acme Telecom", "en", "EUR", "#112233", "a-secret");

        assertThatThrownBy(() -> onboarding.mutateBrand("acme", new BrandPatch(null, null,
                "Fast fibre\n        machine-client-secret: taken")))
                .isInstanceOf(BadRequestException.class);

        assertThat(Files.readString(registry)).doesNotContain("machine-client-secret: taken");
    }

    @Test
    void punctuationInARealBrandNameStillWorksAndReadsBack() throws Exception {
        // The reason validation alone is not enough: real operators are called
        // things like this, and the file must hold them without breaking.
        String awkward = "O'Hara \"Telecom\" A/S: fibre & more \\ 100%";
        onboarding.appendTenantBlock("acme", awkward, "en", "EUR", "#112233", "a-secret");

        assertThat(onboarding.brandOf("acme").name()).isEqualTo(awkward);
    }

    @Test
    void aBrandNameIsLengthBounded() {
        assertThatThrownBy(() -> onboarding.appendTenantBlock(
                "acme", "A".repeat(61), "en", "EUR", "#112233", "a-secret"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("60 characters or fewer");
    }
}
