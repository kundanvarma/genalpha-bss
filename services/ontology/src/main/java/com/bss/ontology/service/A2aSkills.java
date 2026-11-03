package com.bss.ontology.service;

import com.bss.ontology.client.OntologyProperties;
import com.bss.ontology.dto.A2aMessages.Skill;
import com.bss.ontology.dto.A2aMessages.TaskFacts;
import com.bss.ontology.dto.A2aMessages.TaskStatus;
import com.bss.ontology.dto.Check;
import com.bss.ontology.dto.ExecuteReceipt;
import com.bss.ontology.dto.Explanation;
import com.bss.ontology.registry.Registry;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a partner's agent may delegate through the A2A door (#426), and how each
 * delegation is answered. Every skill is a governed action of the registry or a
 * read of it; the door adds no rights and removes none:
 *
 * <ul>
 *   <li>{@code check-pick-benefit} — the dry run of {@code pickBenefit} through
 *       {@link ActionCheckService}: every precondition, the permission, the policy
 *       and the autonomy gate, with the caller's token. Nothing changes and no
 *       receipt is written — the task says so.</li>
 *   <li>{@code explain-action} — the registry's definition of an action in words.</li>
 *   <li>{@code pick-benefit} — OFF unless the operator enables it
 *       ({@code ontology.a2a.execute-enabled}); then {@link ActionExecuteService}
 *       checks again, writes the attempt before dispatching (ADR 0025) and the
 *       decision receipt after. The task carries the decision id.</li>
 * </ul>
 *
 * The card lists exactly {@link #offered(String)}; a skill that is not offered is
 * refused here too, so the card and the door cannot disagree.
 */
@Service
public class A2aSkills {

    public static final String CHECK_PICK_BENEFIT = "check-pick-benefit";
    public static final String EXPLAIN_ACTION = "explain-action";
    public static final String PICK_BENEFIT = "pick-benefit";
    /** Who the receipt names when the partner's agent does not say: a registered external agent. */
    public static final String DEFAULT_AGENT = "external-a2a";

    private static final String PICK = "pickBenefit";
    private static final List<String> JSON = List.of("application/json");
    private static final List<String> JSON_AND_TEXT = List.of("application/json", "text/plain");

    private final Registry registry;
    private final ActionCheckService checks;
    private final ActionExecuteService executes;
    private final ExplainService explain;
    private final OntologyProperties props;

    public A2aSkills(Registry registry, ActionCheckService checks, ActionExecuteService executes, ExplainService explain,
            OntologyProperties props) {
        this.registry = registry;
        this.checks = checks;
        this.executes = executes;
        this.explain = explain;
        this.props = props;
    }

    /** How one delegation ended: the A2A state, the words for the partner, the result document, and the receipt facts. */
    public record Result(String state, String said, Object result, TaskFacts facts) {
    }

    /** The skills this tenant's door actually serves — the card names these and nothing else. */
    public List<Skill> offered(String tenant) {
        Registry.Layer layer = registry.forTenant(tenant);
        List<Skill> skills = new ArrayList<>();
        boolean pick = live(layer.actions().get(PICK));
        if (pick) {
            skills.add(new Skill(CHECK_PICK_BENEFIT, "Can this household pick this benefit?",
                    "A dry run of picking a benefit with one of the household's Choices: every condition with its verdict "
                            + "(the benefit is on offer, the caller may order for this household, the business rules agree). "
                            + "Nothing changes and nothing is spent. Send a data part {\"skill\":\"" + CHECK_PICK_BENEFIT
                            + "\",\"inputs\":{\"customerId\":…,\"benefitOfferingId\":…}}.",
                    List.of("benefit", "eligibility", "dry-run"), List.of(), JSON, JSON_AND_TEXT));
        }
        skills.add(new Skill(EXPLAIN_ACTION, "Explain a governed action",
                "What a business action of this operator means, what it needs, who may do it and what follows — the "
                        + "registry's own definition in words. Send {\"skill\":\"" + EXPLAIN_ACTION + "\",\"inputs\":{\"action\":\"pickBenefit\"}}.",
                List.of("explain", "ontology"), List.of(), JSON, JSON_AND_TEXT));
        if (pick && props.getA2a().isExecuteEnabled()) {
            skills.add(new Skill(PICK_BENEFIT, "Pick a benefit for a household",
                    "Pick the benefit with one of the household's Choices, as the person whose token you carry. Every "
                            + "condition is checked again first; a refusal names the condition. Writes a decision receipt; "
                            + "the task's metadata carries its id.",
                    List.of("benefit", "order"), List.of(), JSON, JSON_AND_TEXT));
        }
        return skills;
    }

    public boolean isOffered(String skill, String tenant) {
        return offered(tenant).stream().anyMatch(s -> s.id().equals(skill));
    }

    /** Run one delegated skill for the caller. {@code inputs} is the data part's {@code inputs} object (or missing). */
    public Result run(String skill, JsonNode inputs, Caller caller) {
        if (skill == null || skill.isBlank()) {
            return rejected(null, null, "say which skill: send a data part {\"skill\": …, \"inputs\": {…}} — the Agent Card lists them");
        }
        if (!isOffered(skill, caller.tenant())) {
            return rejected(skill, null, "this agent offers no skill \"" + skill + "\" — the Agent Card lists the ones it does");
        }
        Map<String, String> in = inputs(inputs);
        return switch (skill) {
            case CHECK_PICK_BENEFIT -> check(skill, in, caller);
            case EXPLAIN_ACTION -> explain(skill, in, caller);
            case PICK_BENEFIT -> execute(skill, in, caller);
            default -> rejected(skill, null, "this agent offers no skill \"" + skill + "\"");
        };
    }

    private Result check(String skill, Map<String, String> in, Caller caller) {
        JsonNode action = registry.forTenant(caller.tenant()).actions().get(PICK);
        String missing = missing(action, in);
        if (missing != null) {
            return new Result(TaskStatus.INPUT_REQUIRED, missing, null, new TaskFacts(skill, PICK, true, null, null, null));
        }
        Check c = checks.check(action, in, caller);
        String said = c.allowed() ? "Yes: every condition holds; picking this benefit would be allowed."
                : "No: " + c.refusal() + ".";
        return new Result(TaskStatus.COMPLETED, said, c, new TaskFacts(skill, PICK, true, null, null,
                "none — a dry run changes nothing, so it writes no receipt"));
    }

    private Result explain(String skill, Map<String, String> in, Caller caller) {
        String name = in.get("action");
        if (name == null || name.isBlank()) {
            return new Result(TaskStatus.INPUT_REQUIRED, "say which action: inputs.action, e.g. \"pickBenefit\"", null,
                    new TaskFacts(skill, null, null, null, null, null));
        }
        Explanation e = explain.action(name, caller.tenant());
        if (e == null) {
            return rejected(skill, name, "this operator has no action named \"" + name + "\"");
        }
        return new Result(TaskStatus.COMPLETED, e.text(), e, new TaskFacts(skill, name, null, null, null, null));
    }

    private Result execute(String skill, Map<String, String> in, Caller caller) {
        JsonNode action = registry.forTenant(caller.tenant()).actions().get(PICK);
        String missing = missing(action, in);
        if (missing != null) {
            return new Result(TaskStatus.INPUT_REQUIRED, missing, null, new TaskFacts(skill, PICK, false, null, null, null));
        }
        ActionExecuteService.Outcome o = executes.execute(action, in, caller);
        ExecuteReceipt r = o.body();
        String said = r == null || r.said() == null ? (o.done() ? "Done." : "Not done.") : r.said();
        String decisionId = r == null ? null : r.decisionId();
        String approvalId = r == null ? null : r.approvalId();
        String state;
        String receipt;
        if (o.done()) {
            state = TaskStatus.COMPLETED;
            receipt = "written — the decision log holds it under the decision id";
        } else if (o.status() == 202) {
            // filed for a person: the task is not over, but this door does not follow the approval
            state = TaskStatus.WORKING;
            receipt = "written — filed for a human approver";
        } else if (o.status() == 422 || o.status() == 410 || (r != null && r.componentStatus() != null && o.status() < 500)) {
            state = TaskStatus.REJECTED;
            receipt = "the refusal is recorded in the receipt store";
        } else {
            state = TaskStatus.FAILED;
            receipt = o.status() == 504 ? "the attempt is recorded; its outcome is uncertain until reconciled"
                    : "the attempt was not dispatched";
        }
        return new Result(state, said, r, new TaskFacts(skill, PICK, false, decisionId, approvalId, receipt));
    }

    private static Result rejected(String skill, String action, String why) {
        return new Result(TaskStatus.REJECTED, why, null, new TaskFacts(skill, action, null, null, null, null));
    }

    /** The first required input of the action that was not given, in the action's own words — or null. */
    private static String missing(JsonNode action, Map<String, String> in) {
        for (JsonNode i : action.path("inputs")) {
            String name = i.path("name").asString();
            if (i.path("required").asBoolean(false) && (in.get(name) == null || in.get(name).isBlank())) {
                return name + " is required — " + i.path("meaning").asString(name);
            }
        }
        return null;
    }

    private static boolean live(JsonNode action) {
        return action != null && !"deprecated".equals(action.path("status").asString());
    }

    /** The inputs by name; a JSON null is an input not given (as at the check/execute door). */
    static Map<String, String> inputs(JsonNode node) {
        Map<String, String> in = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return in;
        }
        node.properties().forEach(f -> {
            if (!f.getValue().isNull()) {
                in.put(f.getKey(), f.getValue().asString());
            }
        });
        return in;
    }
}
