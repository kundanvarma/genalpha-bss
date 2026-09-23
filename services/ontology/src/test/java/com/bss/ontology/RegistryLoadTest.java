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
        assertThat(core.agents()).containsKeys("care-assist", "external-mcp", "hermes-worker");
        for (JsonNode ag : core.agents().values()) {
            for (JsonNode x : ag.path("actions").path("execute")) {
                assertThat(core.actions()).as("agent " + ag.path("agent").asText() + " executes a known action").containsKey(x.asText());
            }
        }
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

    /* ---------- an overlay may tighten an action, never loosen it ----------
     *
     * Preconditions were always append-only, so the hard ceilings held. What an
     * overlay could still do was take the governance block whole, and with it
     * remove the human approver that stands between a care agent and a credit.
     */

    /** The repo ontology with one tenant overlay written over issueCredit. */
    private static Path withCreditOverlay(String governance) throws IOException {
        Path tmp = Files.createTempDirectory("ontology-overlay");
        copy(Path.of(repoOntology()), tmp);
        Path dir = tmp.resolve("tenants/taranga/actions");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("issueCredit.yml"), """
                action: issueCredit
                governance:
                %s
                """.formatted(governance));
        return tmp;
    }

    @Test
    void anOverlayMayNotDropTheHumanApproverFromACredit() throws IOException {
        Path tmp = withCreditOverlay("  approval: none");

        assertThatThrownBy(() -> new Registry(tmp.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("may not loosen approval")
                .hasMessageContaining("never loosen it");
    }

    @Test
    void anOverlayMayNotRaiseTheAmountAboveWhichAHumanDecides() throws IOException {
        // core: a human decides above 25. An overlay asking for 45 buys a desk
        // agent twenty more pounds of unreviewed goodwill per credit.
        Path tmp = withCreditOverlay("  approvalAbove: { input: amount, amount: 45 }");

        assertThatThrownBy(() -> new Registry(tmp.toString()))
                .hasMessageContaining("may not loosen approvalAbove.amount from 25 to 45");
    }

    @Test
    void anOverlayMayNotRemoveTheApproverOrWeakenTheAudit() throws IOException {
        // An approver cannot be deleted: the merge starts from the core block,
        // so omitting the key keeps it, and blanking it is refused by the
        // schema's own pattern before the tightening rule is reached.
        assertThatThrownBy(() -> new Registry(withCreditOverlay("  approverRole: \"\"").toString()))
                .hasMessageContaining("approverRole");
        assertThatThrownBy(() -> new Registry(withCreditOverlay("  audit: optional").toString()))
                .hasMessageContaining("may not loosen audit");
        assertThatThrownBy(() -> new Registry(withCreditOverlay("  autonomy: high").toString()))
                .hasMessageContaining("may not loosen autonomy from \"low\" to \"high\"");
        assertThatThrownBy(() -> new Registry(withCreditOverlay("  limits: { maxAmount: 5000 }").toString()))
                .hasMessageContaining("may not loosen limits.maxAmount from 50 to 5000");
    }

    @Test
    void anOverlayMayTightenAndSilenceNeverDropsACoreGuard() throws IOException {
        // lower ceiling, lower approval threshold, and NOTHING said about the
        // approver or the audit — both of which must survive the merge
        Path tmp = withCreditOverlay("  approvalAbove: { input: amount, amount: 10 }\n  limits: { maxAmount: 30 }");

        Registry r = new Registry(tmp.toString());
        JsonNode g = r.forTenant("taranga").actions().get("issueCredit").path("governance");

        assertThat(g.path("approvalAbove").path("amount").asInt()).isEqualTo(10);
        assertThat(g.path("limits").path("maxAmount").asInt()).isEqualTo(30);
        assertThat(g.path("approverRole").asText()).isEqualTo("billing:admin");
        assertThat(g.path("approval").asText()).isEqualTo("human");
        assertThat(g.path("audit").asText()).isEqualTo("mandatory");
    }

    @Test
    void theShippedOverlayStillLoadsAndItsOwnLimitSurvives() throws IOException {
        // the real taranga overlay tightens upgradeSubscription; it must not be
        // collateral damage of the new rule
        Registry r = new Registry(repoOntology());
        JsonNode g = r.forTenant("taranga").actions().get("upgradeSubscription").path("governance");

        assertThat(g.path("limits").path("maxMonthlyPriceNok").asInt()).isEqualTo(999);
        assertThat(g.path("audit").asText()).isEqualTo("mandatory");
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
