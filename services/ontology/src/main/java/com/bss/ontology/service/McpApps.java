package com.bss.ontology.service;

import com.bss.ontology.dto.Check;
import com.bss.ontology.dto.McpMessages;
import tools.jackson.databind.JsonNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP Apps (SEP-1865): the cards this server hands a host to render instead of
 * asking the model to describe a decision in prose. One today — the pick-benefit
 * confirm card — because "AI proposes, a human decides" is easiest to keep when
 * the deciding is a button.
 *
 * The card is linked from {@code check_pick_benefit}, the DRY RUN, not from
 * {@code pick_benefit}: a host renders the card when the tool's result arrives,
 * and a card on the executing tool would arrive after the Choice was already
 * spent. The dry run resolves the benefit and its cost; the card shows them in
 * words and its Confirm button asks the host to call {@code pick_benefit} — which
 * runs every precondition, permission and policy again, with the person's token,
 * and writes the receipt. The card decides nothing.
 */
@Component
public class McpApps {

    public static final String MIME = "text/html;profile=mcp-app";
    public static final String PICK_BENEFIT_CARD = "ui://genalpha-ontology/pick-benefit-confirm";
    public static final String ACTION = "pickBenefit";
    public static final String CHECK_TOOL = "check_pick_benefit";
    public static final String CONFIRM_TOOL = "pick_benefit";

    private static final McpMessages.ResourceMeta UI_META = new McpMessages.ResourceMeta(
            new McpMessages.UiResource(new McpMessages.Csp(List.of(), List.of()), true));

    private final String pickBenefitHtml;

    public McpApps() {
        this.pickBenefitHtml = load("mcp-apps/pick-benefit-confirm.html");
    }

    private static String load(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("the MCP Apps card " + path + " is missing from the jar", e);
        }
    }

    /** The cards, for {@code resources/list}, when the tenant's registry offers the action they confirm. */
    public List<McpMessages.Resource> resources(Map<String, JsonNode> actions) {
        if (!offered(actions)) {
            return List.of();
        }
        return List.of(new McpMessages.Resource(PICK_BENEFIT_CARD, "pick-benefit-confirm", "Pick a benefit — confirm",
                "A card that shows the benefit by name and its cost in Choices, with a Confirm button that calls "
                        + CONFIRM_TOOL + " through the host.", MIME, UI_META));
    }

    /** {@code resources/read} of a card. */
    public Optional<McpMessages.ResourceContent> read(String uri, Map<String, JsonNode> actions) {
        if (PICK_BENEFIT_CARD.equals(uri) && offered(actions)) {
            return Optional.of(new McpMessages.ResourceContent(PICK_BENEFIT_CARD, MIME, pickBenefitHtml, UI_META));
        }
        return Optional.empty();
    }

    /** {@code _meta.ui} of a generated tool: only the dry run of pickBenefit carries a card. */
    public McpMessages.ToolMeta metaFor(String toolName) {
        if (CHECK_TOOL.equals(toolName)) {
            return new McpMessages.ToolMeta(new McpMessages.ToolUi(PICK_BENEFIT_CARD, List.of("model", "app")));
        }
        return null;
    }

    /** What the card shows, read from what the dry run resolved — the benefit's name and its cost, never its id. */
    public McpMessages.ToolResultMeta cardFor(String toolName, Map<String, String> inputs, Check check) {
        if (!CHECK_TOOL.equals(toolName) || check == null) {
            return null;
        }
        JsonNode offering = null;
        if (check.resolved() != null) {
            offering = check.resolved().has("benefitOffering") ? check.resolved().get("benefitOffering")
                    : check.resolved().get("benefitOfferingAnyChannel");
        }
        String name = offering == null ? "" : offering.path("name").asString("");
        Integer cost = offering != null && offering.path("choiceCost").isNumber() ? offering.path("choiceCost").asInt() : null;
        return new McpMessages.ToolResultMeta(new McpMessages.ConfirmCard(ACTION, CONFIRM_TOOL, new LinkedHashMap<>(inputs),
                name.isBlank() ? "the benefit you chose" : name, cost, check.allowed(), check.refusal()));
    }

    private static boolean offered(Map<String, JsonNode> actions) {
        JsonNode a = actions.get(ACTION);
        return a != null && !"deprecated".equals(a.path("status").asString());
    }
}
