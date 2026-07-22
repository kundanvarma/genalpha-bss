package com.bss.intelligence.service;

import com.bss.intelligence.exception.BadRequestException;
import com.bss.intelligence.llm.LlmAdapter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The product copilot: a product owner CHATS about the product they want to
 * sell; the copilot explains how it models in TMF620 and, when asked,
 * returns a complete PROPOSAL — specs, prices (conditioned ones included),
 * offerings, categories, bundles. The copilot only ever proposes: the
 * console validates the proposal, shows it as a human-readable card, and a
 * deterministic executor applies it with the product owner's OWN token when
 * they confirm. The model never holds credentials and never writes.
 *
 * Same privilege stance as the CSR copilot: the console sends the catalog
 * context the owner's token can already see; nothing is fetched here.
 */
@Service
public class ProductCopilotService {

    private static final int CONTEXT_CHARS = 4000;
    private static final int HISTORY_TURNS = 12;

    private final LlmAdapter llm;
    private final Redactor redactor;
    private final com.bss.intelligence.llm.AiGovernor governor;
    private final ObjectMapper objectMapper;

    public ProductCopilotService(LlmAdapter llm, Redactor redactor,
            com.bss.intelligence.llm.AiGovernor governor, ObjectMapper objectMapper) {
        this.llm = llm;
        this.redactor = redactor;
        this.governor = governor;
        this.objectMapper = objectMapper;
    }

    // Deliberately NOT @Transactional: when the model misses the contract we
    // throw AFTER auditing both attempts, and a wrapping transaction would
    // roll the audit rows back with the failure — the ledger must keep
    // exactly the turns that failed.
    @SuppressWarnings("unchecked")
    public Map<String, Object> chat(Map<String, Object> request) {
        if (!(request.get("messages") instanceof List<?> messages) || messages.isEmpty()) {
            throw new BadRequestException("messages [{role, content}] are required");
        }
        String system = """
                You are the product copilot of a TM Forum ODA telecom BSS. A product owner \
                describes a product in plain language; you help them model and create it. \
                The modeling rules of this catalog:
                - An OFFERING is what is sold; a SPECIFICATION describes what it is. \
                Configurable characteristics (color, storage) become pickers in the shop; \
                characteristics with "configurable": false render as an About-facts table.
                - CATEGORIES drive placement AND fulfilment: "Mobile plans" and "Broadband" \
                activate network lines with numbers and SIMs; "Devices" ship hardware; \
                "Partner services" mint partner entitlement codes (Netflix-style); \
                "Security" activates a feature; "Insurance" and "Top-ups" only bill; \
                "TV & Add-ons" for TV; "Bundles" for bundles.
                - PRICES: priceType "recurring" with recurringChargePeriodType "month", or \
                "oneTime". A price with prodSpecCharValueUse applies only to matching \
                configurations (e.g. a colour premium) — never one offering per variant.
                - BUNDLES: isBundle true + bundledProductOffering children; a child with \
                bundledProductOfferingOption {numberRelOfferLowerLimit: 0, numberRelOfferUpperLimit: 1} \
                is optional. A commitment is productOfferingTerm \
                [{name, duration: {amount: 12, units: "month"}}].
                - A BUNDLE CHILD KEEPS ITS OWN STANDALONE PRICE. To sell an existing \
                product cheaper alongside a plan ("Netflix for 5 with this plan"), do NOT \
                add a price to the plan — propose a pricingRule with a negative "amount" \
                equal to the reduction, whenCartHas both products.
                - CROSS-PRODUCT DISCOUNTS ("10% off the phone when bought with this plan") \
                are PRICING RULES, not prices: propose them as pricingRules entries with \
                whenCartHas listing the offerings that must be in the cart together \
                (a ref from this proposal, or the exact name of an existing offering). \
                adjustmentValue is negative for a discount.
                Respond with ONLY a JSON object, no markdown fences, shaped:
                {"kind": "question"|"advice"|"proposal", "message": "<what you say to the owner>", \
                "proposal": null or {"specs": [{"ref": "s1", "name", "brand"?, "productSpecCharacteristic": []}], \
                "prices": [{"ref": "p1", "name", "priceType", "recurringChargePeriodType"?, \
                "price": {"unit", "value"}, "prodSpecCharValueUse"?}], \
                "offerings": [{"ref": "o1", "name", "description", "category": [{"name"}], \
                "specRef"?, "priceRefs": [], "isBundle"?, "productOfferingTerm"?, \
                "bundledChildren"?: [{"offeringRef" or "existingName", "optional": true|false}]}], \
                "pricingRules": [{"name", "message", "adjustmentType": "percent"|"amount", \
                "adjustmentValue": -10, "whenCartHas": ["o1", "Samsung Galaxy S26"], \
                "audience": "all"|"consumer"|"business"}], \
                "experienceRules": [{"name", "whenInterest": "<catalog category the guest \
                browsed>", "banner": "<what the shop says to them>", \
                "pinOffering"?: "o1" or the exact name of an existing offering}]}
                audience "consumer" limits a discount to private customers (it will not \
                apply to company purchases or consolidated business invoices).
                PERSONALIZATION (experienceRules) changes what a CONSENTING guest SEES — \
                banner copy and one pinned offering when their browsing interest matches a \
                category — never a price; discounts stay in pricingRules.
                A proposal MUST contain at least one offering — specs and prices alone sell \
                nothing (proposals that only add pricingRules or experienceRules to \
                EXISTING offerings are the exceptions). Every price needs an offering whose \
                priceRefs uses it. \
                Example of a complete minimal proposal: {"kind":"proposal","message":"...", \
                "proposal":{"specs":[{"ref":"s1","name":"City Plan","productSpecCharacteristic":[]}], \
                "prices":[{"ref":"p1","name":"City Plan Monthly","priceType":"recurring", \
                "recurringChargePeriodType":"month","price":{"unit":"EUR","value":19.99}}], \
                "offerings":[{"ref":"o1","name":"City Plan","description":"...", \
                "category":[{"name":"Mobile plans"}],"specRef":"s1","priceRefs":["p1"]}]}}
                HARD RULES: prices are ALWAYS positive — NEVER express a discount as a \
                price, discounts go ONLY in pricingRules. whenCartHas entries must be refs \
                from this proposal or names copied EXACTLY from the catalog context — never \
                substitute a different product than the owner named. A binding period \
                ("6 month binding") is productOfferingTerm on the offering, not a price. \
                prodSpecCharValueUse must be a LIST of objects like \
                [{"name": "color", "productSpecCharacteristicValue": [{"value": "Titanium"}]}] \
                — OMIT it entirely unless the price depends on a configured characteristic; \
                never use it for bundle membership or descriptions.
                Ask a question when the ask is ambiguous; give advice when they want to \
                understand; produce a proposal when they ask you to create or they have \
                answered your questions. Use the tenant's existing categories and currency. \
                Keep names short and sellable.""";

        StringBuilder conversation = new StringBuilder();
        Object catalog = request.get("catalog");
        if (catalog != null) {
            conversation.append("Catalog context (what exists today):\n")
                    .append(cap(toJson(catalog))).append("\n---\n");
        }
        List<Map<String, Object>> turns = (List<Map<String, Object>>) messages;
        for (Map<String, Object> turn : turns.subList(Math.max(0, turns.size() - HISTORY_TURNS), turns.size())) {
            conversation.append("owner".equalsIgnoreCase(String.valueOf(turn.get("role"))) || "user".equalsIgnoreCase(String.valueOf(turn.get("role")))
                    ? "OWNER: " : "COPILOT: ");
            conversation.append(redactor.redact(String.valueOf(turn.getOrDefault("content", "")))).append("\n");
        }

        String raw = governor.complete("product-copilot",
                com.bss.intelligence.llm.LlmAdapter.Tier.SMART, system, conversation.toString());
        Map<String, Object> parsed = parse(raw);
        if (parsed == null) {
            raw = governor.complete("product-copilot-retry",
                    com.bss.intelligence.llm.LlmAdapter.Tier.SMART, system, conversation
                    + "\nYour previous answer was not the required bare JSON object. Respond again"
                    + " with ONLY the JSON object described in the instructions.");
            parsed = parse(raw);
        }
        if (parsed == null) {
            throw new BadRequestException("the model did not follow the copilot JSON contract");
        }
        parsed.put("provider", llm.provider());
        parsed.put("model", llm.model());
        return parsed;
    }

    /** Markdown-tolerant JSON parse: strip fences, find the outermost object. */
    private Map<String, Object> parse(String raw) {
        if (raw == null) {
            return null;
        }
        String body = raw.trim().replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("(?s)```\\s*$", "");
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(body.substring(start, end + 1),
                    new TypeReference<Map<String, Object>>() {
                    });
            String kind = String.valueOf(parsed.get("kind"));
            if (!List.of("question", "advice", "proposal").contains(kind) || parsed.get("message") == null) {
                return null;
            }
            if ("proposal".equals(kind) && !(parsed.get("proposal") instanceof Map)) {
                // mechanical repair for a common small-model miss: the
                // proposal's parts emitted at the TOP level with proposal null
                Map<String, Object> lifted = new java.util.LinkedHashMap<>();
                for (String key : List.of("specs", "prices", "offerings", "pricingRules",
                        "experienceRules")) {
                    if (parsed.get(key) instanceof List<?> list && !list.isEmpty()) {
                        lifted.put(key, list);
                    }
                }
                if (lifted.isEmpty()) {
                    return null;
                }
                parsed.put("proposal", lifted);
            }
            return parsed;
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BadRequestException("catalog context is not serializable");
        }
    }

    private String cap(String s) {
        return s.length() <= CONTEXT_CHARS ? s : s.substring(0, CONTEXT_CHARS);
    }

}
