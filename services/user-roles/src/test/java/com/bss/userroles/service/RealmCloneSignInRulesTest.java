package com.bss.userroles.service;

import com.bss.userroles.security.TenantFileRefresher;
import com.bss.userroles.security.TenantRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A new tenant's sign-in follows the rules from its first minute (#324): the realm
 * onboarding creates already carries the password policy and the plain-language
 * messages, from the SAME files the fleet's converge step applies to every realm.
 */
class RealmCloneSignInRulesTest {

    private static final Path SECURITY = Path.of("../../infra/keycloak/security");

    private static TenantOnboardingService onboarding(String securityDir) {
        return new TenantOnboardingService(RestClient.builder(),
                "http://localhost:8085", "", "admin", "admin", "", securityDir,
                "../../infra/keycloak/nova-realm.json", "unused-tenants.yml",
                "http://localhost:8081", "http://localhost:8113", "http://localhost:8083",
                "http://localhost:8097", "http://localhost:8086", "http://localhost:8104",
                "http://localhost:8084", "genalpha,nova",
                mock(IdpAdminClient.class), mock(TenantRegistry.class), mock(TenantFileRefresher.class));
    }

    @Test
    void aClonedRealmCarriesThePolicyAndItsWordsInEveryLocale() throws Exception {
        JsonNode realm = new ObjectMapper().readTree(onboarding(SECURITY.toString()).realmClone("acme", "Acme", "s3cret-acme"));
        String policy = Files.readString(SECURITY.resolve("password-policy")).trim();
        assertThat(realm.path("passwordPolicy").asString()).isEqualTo(policy).contains("length(15)");
        for (String locale : new String[] {"en", "no"}) {
            assertThat(realm.path("localizationTexts").path(locale).path("invalidPasswordMinLengthMessage").asString())
                    .as(locale).isNotBlank().contains("{0}");
        }
        // and still no template user travels with it
        assertThat(realm.path("users").size()).isZero();
    }

    @Test
    void withoutTheSettingsTheCloneIsTheTemplatesAsBefore() throws Exception {
        JsonNode realm = new ObjectMapper().readTree(onboarding("does-not-exist").realmClone("acme", "Acme", "s3cret-acme"));
        assertThat(realm.has("passwordPolicy")).isFalse();
    }
}
