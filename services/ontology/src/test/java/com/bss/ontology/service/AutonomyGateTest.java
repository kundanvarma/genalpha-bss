package com.bss.ontology.service;

import com.bss.ontology.dto.Verdict;
import com.bss.ontology.registry.Registry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HOW MUCH OF THIS MAY A MACHINE DO ALONE — the rule, pinned.
 *
 * <p>{@code governance.autonomy} was written on every action from the start and
 * read by nothing at execution time: a field that looked like a control and was
 * not one. Anyone auditing the registry concluded a SIM swap was gated when
 * nothing gated it. These tests exist so that deleting the gate turns something
 * red.
 *
 * <p>The cases that matter most are the two failure directions: a machine must
 * not get <i>more</i> freedom by staying quiet about being one, and an
 * unrecognised autonomy value must refuse rather than fall through to the
 * permissive branch.
 */
class AutonomyGateTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode action(String name, String autonomy) {
        return JSON.readTree("{\"action\":\"" + name + "\",\"governance\":{\"autonomy\":\"" + autonomy + "\"}}");
    }

    private static Registry.Layer layerWith(String agentId, String agentAutonomy) {
        Map<String, JsonNode> agents = new TreeMap<>();
        if (agentId != null) {
            agents.put(agentId, JSON.readTree("{\"agent\":\"" + agentId + "\",\"autonomy\":\"" + agentAutonomy + "\"}"));
        }
        return new Registry.Layer(new TreeMap<>(), new TreeMap<>(), new TreeMap<>(), new TreeMap<>(), agents);
    }

    private static Caller person() {
        return new Caller("t", "pat", Set.of("service:write"), "console", "genalpha", null);
    }

    /** Names an agent in the header — self-declared, so it may only ever tighten. */
    private static Caller declaring(String agent) {
        return new Caller("t", "pat", Set.of("service:write"), "console", "genalpha", agent);
    }

    /** Holds the digital-worker badge — authenticated, and true whatever it calls itself. */
    private static Caller badgedButSilent() {
        return new Caller("t", "worker-1", Set.of("service:write", "workforce:use"), "console", "genalpha", null);
    }

    @Test
    void aPersonIsNotJudgedByThisRuleAtAll() {
        assertThat(ActionCheckService.autonomy(action("replaceSim", "low"), person(), layerWith(null, null)))
                .as("autonomy says nothing about a human at a screen")
                .isNull();
    }

    @Test
    void aMachineMayNotSwapASimAlone() {
        Verdict v = ActionCheckService.autonomy(action("replaceSim", "low"),
                declaring("hermes-worker"), layerWith("hermes-worker", "policy-autonomous"));
        assertThat(v).isNotNull();
        assertThat(v.ok()).isFalse();
        assertThat(v.says()).contains("a person must do it");
    }

    @Test
    void stayingQuietAboutBeingAMachineDoesNotBuyFreedom() {
        // THE BYPASS THIS GUARDS. X-GenAlpha-Agent is an unverified header, so a
        // robot could simply omit it. The badge is in the token and cannot be.
        Verdict v = ActionCheckService.autonomy(action("replaceSim", "low"),
                badgedButSilent(), layerWith(null, null));
        assertThat(v).isNotNull();
        assertThat(v.ok())
                .as("a badged worker is a machine even when it names no agent")
                .isFalse();
    }

    @Test
    void anUnrecognisedAutonomyValueFailsClosed() {
        // 'none' is the STRICTEST value in the registry's ranking
        // (none < low < medium < high). Treating anything-not-high as 'medium'
        // would have made the strictest value the most permissive.
        assertThat(ActionCheckService.autonomy(action("approveLaunch", "none"),
                declaring("hermes-worker"), layerWith("hermes-worker", "policy-autonomous")).ok())
                .as("'none' must refuse")
                .isFalse();
        assertThat(ActionCheckService.autonomy(action("approveLaunch", "banana"),
                declaring("hermes-worker"), layerWith("hermes-worker", "policy-autonomous")).ok())
                .as("an unknown value must refuse, not fall through")
                .isFalse();
    }

    @Test
    void highLetsAMachineActAlone() {
        Verdict v = ActionCheckService.autonomy(action("restartRouter", "high"),
                declaring("hermes-worker"), layerWith("hermes-worker", "policy-autonomous"));
        assertThat(v.ok()).isTrue();
    }

    @Test
    void mediumAdmitsOnlyRegisteredAgentsTrustedToActUnderPolicy() {
        // unregistered — the caller claims an agent nobody declared
        assertThat(ActionCheckService.autonomy(action("issueCredit", "medium"),
                declaring("not-a-real-agent"), layerWith("hermes-worker", "policy-autonomous")).ok())
                .as("an unregistered agent is refused")
                .isFalse();

        // registered, but advisory: it proposes, it does not act
        assertThat(ActionCheckService.autonomy(action("issueCredit", "medium"),
                declaring("knowledge-ask"), layerWith("knowledge-ask", "advisory")).ok())
                .as("an advisory agent is refused")
                .isFalse();

        // registered and trusted to act under policy
        assertThat(ActionCheckService.autonomy(action("issueCredit", "medium"),
                declaring("hermes-worker"), layerWith("hermes-worker", "policy-autonomous")).ok())
                .as("a policy-autonomous agent may act")
                .isTrue();

        // badged but naming nobody: medium needs a KNOWN agent, so still refused
        assertThat(ActionCheckService.autonomy(action("issueCredit", "medium"),
                badgedButSilent(), layerWith("hermes-worker", "policy-autonomous")).ok())
                .as("a badge alone is not a registered agent")
                .isFalse();
    }
}
