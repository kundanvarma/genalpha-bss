package com.bss.ontology;

import com.bss.ontology.registry.Registry;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The registry validates itself: schemas, references, tenant overlay rules.
 * This is the CI half of conformance (suite #125 is the runtime half).
 */
class RegistryLoadTest {

    private static String repoOntology() {
        Path p = Path.of("..", "..", "ontology").toAbsolutePath().normalize();
        assertThat(Files.isDirectory(p)).as("repo ontology dir at " + p).isTrue();
        return p.toString();
    }

    @Test
    void theRepoRegistryLoadsAndResolvesEveryReference() throws IOException {
        Registry r = new Registry(repoOntology());
        Registry.Layer core = r.core();
        assertThat(core.actions()).containsKey("upgradeSubscription");
        assertThat(core.concepts()).containsKeys("Subscription", "ProductOffering", "Customer", "Service", "ProductOrder", "Bill", "Entitlement");
        JsonNode a = core.actions().get("upgradeSubscription");
        assertThat(core.capabilities()).containsKey(a.path("executes").path("capability").asText());
        for (JsonNode e : a.path("emits")) {
            assertThat(core.components()).containsKey(e.path("component").asText());
        }
    }

    @Test
    void theTenantOverlayAddsGuardrailsButKeepsEveryCorePrecondition() throws IOException {
        Registry r = new Registry(repoOntology());
        JsonNode core = r.core().actions().get("upgradeSubscription");
        JsonNode taranga = r.forTenant("taranga").actions().get("upgradeSubscription");
        assertThat(taranga.path("tenantExtended").asBoolean()).isTrue();
        assertThat(taranga.path("preconditions").size()).isEqualTo(core.path("preconditions").size() + 1);
        assertThat(taranga.path("executes")).isEqualTo(core.path("executes"));
        assertThat(r.forTenant("genalpha").actions().get("upgradeSubscription").has("tenantExtended")).isFalse();
    }

    @Test
    void aDanglingReferenceFailsAtLoad() throws IOException {
        Path tmp = Files.createTempDirectory("ontology-bad");
        Path src = Path.of(repoOntology());
        copy(src, tmp);
        Files.writeString(tmp.resolve("actions/broken.yml"), """
                action: breakThings
                version: 1
                introduced: 2026-09-11
                status: active
                meaning: An action that names a capability nobody has, to prove the registry refuses it.
                concept: Subscription
                inputs: []
                preconditions: []
                permissions: { anyOf: [ { role: ordering:write } ] }
                governance: { autonomy: low, approval: human, audit: mandatory }
                executes: { capability: nothing.here }
                emits: []
                """);
        assertThatThrownBy(() -> new Registry(tmp.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nothing.here");
    }

    @Test
    void deprecationIsAVersionedContract() throws IOException {
        Path tmp = Files.createTempDirectory("ontology-deprecated");
        copy(Path.of(repoOntology()), tmp);
        String base = """
                action: %s
                version: 2
                introduced: 2026-09-11
                status: deprecated
                %s
                meaning: A retired way of changing a plan, kept callable until its date and pointing at its successor.
                concept: Subscription
                inputs: []
                preconditions: []
                permissions: { anyOf: [ { role: ordering:write } ] }
                governance: { autonomy: low, approval: human, audit: mandatory }
                executes: { capability: productOrdering.create }
                emits: []
                """;
        // no date: refused at load
        Files.writeString(tmp.resolve("actions/oldChangePlan.yml"), base.formatted("oldChangePlan", "supersededBy: upgradeSubscription"));
        assertThatThrownBy(() -> new Registry(tmp.toString())).hasMessageContaining("deprecated date");
        // a successor nobody knows: refused at load
        Files.writeString(tmp.resolve("actions/oldChangePlan.yml"), base.formatted("oldChangePlan", "deprecated: 2026-12-31\nsupersededBy: nothingLikeIt"));
        assertThatThrownBy(() -> new Registry(tmp.toString())).hasMessageContaining("nothingLikeIt");
        // a proper deprecation loads, and the successor is named
        Files.writeString(tmp.resolve("actions/oldChangePlan.yml"), base.formatted("oldChangePlan", "deprecated: 2026-12-31\nsupersededBy: upgradeSubscription"));
        Registry r = new Registry(tmp.toString());
        assertThat(r.core().actions().get("oldChangePlan").path("supersededBy").asText()).isEqualTo("upgradeSubscription");
    }

    private static void copy(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
    }
}
