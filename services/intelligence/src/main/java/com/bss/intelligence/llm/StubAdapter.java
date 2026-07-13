package com.bss.intelligence.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic no-network provider: the compose stack, the E2E suites and
 * air-gapped demos all work with zero API keys. It answers the copy-assistant
 * prompt contract (SUBJECT/BODY lines) from the brief itself, so the feature
 * is demonstrably wired end-to-end even before an operator picks a model.
 */
@Component
@ConditionalOnProperty(name = "bss.intelligence.provider", havingValue = "stub", matchIfMissing = true)
public class StubAdapter implements LlmAdapter {

    @Override
    public String complete(String system, String user) {
        if (system.contains("product copilot")) {
            return productCopilot(user);
        }
        if (system.contains("SUMMARY:")) {
            return "SUMMARY: The provided customer data is summarized deterministically (stub"
                    + " provider — configure a real model for genuine insight). Nothing here"
                    + " left the machine.\n"
                    + "NEXT: Review the open items in the 360 with the customer.\n"
                    + "NEXT: Check whether the current plan still fits their usage.";
        }
        if (system.contains("PLACE:")) {
            String ask = user.toLowerCase();
            boolean stadium = ask.contains("stadium") || ask.contains("arena") || ask.contains("tournament");
            String place = ask.contains("stadium-north") ? "stadium-north"
                    : stadium ? "stadium-north" : "customer-site";
            boolean ai = ask.contains("ai") || ask.contains("glass") || ask.contains("inference")
                    || ask.contains("overlay");
            return "NAME: " + (stadium ? "Stadium experience slice" : "B2B connectivity intent") + "\n"
                    + "PLACE: " + place + "\n"
                    + "LATENCY_MS: " + (stadium ? "8" : "40") + "\n"
                    + "BANDWIDTH_MBPS: " + (stadium ? "2000" : "500") + "\n"
                    + "AI_TOKENS_MILLIONS: " + (ai ? "80" : "0");
        }
        if (system.contains("NARRATIVE:")) {
            return "NARRATIVE: This quote packages guaranteed venue connectivity with"
                    + " edge AI inference next to the crowd. AI usage is token-metered:"
                    + " an included allowance, then a fixed price per million tokens —"
                    + " costs scale with the event, not ahead of it. (Stub provider.)";
        }
        if (system.contains("REPLY:")) {
            return "REPLY: Hi! Thanks for reaching out — we are looking into your case and"
                    + " will keep you posted here. (Drafted by the stub provider; configure"
                    + " a real model for tailored replies.)";
        }
        String brief = user.replaceAll("(?s).*Brief:\\s*", "").replaceAll("(?s)\\n.*", "").trim();
        String subject = brief.isEmpty() ? "A little something for you"
                : Character.toUpperCase(brief.charAt(0)) + brief.substring(1);
        boolean promo = user.contains("promotion code");
        String body = promo
                ? "Hi! " + subject + " — use code {code} on your next order. It is our way of saying thanks."
                : "Hi! " + subject + " — thanks for being with us.";
        return "SUBJECT: " + subject + "\nBODY: " + body;
    }

    /**
     * Deterministic product-copilot scenarios: enough conversation shape to
     * prove the chat -> proposal -> confirm -> created loop end to end with
     * no model anywhere. A real provider handles the open-ended asks.
     */
    private String productCopilot(String user) {
        String convo = user.toLowerCase();
        // the LAST thing the owner said decides the scenario — earlier turns
        // about another product must not hijack a new ask
        String lastOwner = convo;
        int idx = convo.lastIndexOf("owner:");
        if (idx >= 0) {
            int end = convo.indexOf("copilot:", idx);
            lastOwner = end > idx ? convo.substring(idx, end) : convo.substring(idx);
        }
        boolean asksWatch = lastOwner.contains("smartwatch") || lastOwner.contains("kids watch");
        boolean asksPlanWithDiscount = (lastOwner.contains("50 gb") || lastOwner.contains("50gb"))
                && (lastOwner.contains("discount") || lastOwner.contains("samsung"));
        if (asksPlanWithDiscount) {
            return "{\"kind\":\"proposal\",\"message\":\"The plan is a catalog offering; the"
                    + " Samsung discount is a PRICING RULE — it fires when both are in the cart,"
                    + " on the preview and on the bill. One confirmation creates both.\","
                    + "\"proposal\":{"
                    + "\"specs\":[{\"ref\":\"s1\",\"name\":\"5G Mobile Plan 50 GB\","
                    + "\"productSpecCharacteristic\":[]}],"
                    + "\"prices\":[{\"ref\":\"p1\",\"name\":\"5G 50 GB Monthly\","
                    + "\"priceType\":\"recurring\",\"recurringChargePeriodType\":\"month\","
                    + "\"price\":{\"unit\":\"EUR\",\"value\":24.99}}],"
                    + "\"offerings\":[{\"ref\":\"o1\",\"name\":\"5G Mobile Plan 50 GB\","
                    + "\"description\":\"5G speeds with a 50 GB monthly allowance.\","
                    + "\"category\":[{\"name\":\"Mobile plans\"}],"
                    + "\"specRef\":\"s1\",\"priceRefs\":[\"p1\"]}],"
                    + "\"pricingRules\":[{\"name\":\"Samsung with plan discount\","
                    + "\"message\":\"10% off — Samsung with your new plan\","
                    + "\"adjustmentType\":\"percent\",\"adjustmentValue\":-10,"
                    + "\"audience\":\"consumer\","
                    + "\"whenCartHas\":[\"o1\",\"Samsung Galaxy S26\"]}]}}";
        }
        boolean asksStreaming = !asksWatch
                && (lastOwner.contains("streaming") || convo.contains("streaming"));
        boolean saidMonthly = lastOwner.contains("/month") || lastOwner.contains("per month")
                || lastOwner.contains("monthly") || lastOwner.matches("(?s).*\\d+[.,]\\d{2}.*");
        if (asksStreaming && !saidMonthly) {
            return "{\"kind\":\"question\",\"message\":\"A streaming service fits the"
                    + " 'Partner services' category — the orchestrator will mint a partner"
                    + " entitlement code at activation, and billing carries the charge."
                    + " What should it cost per month?\",\"proposal\":null}";
        }
        if (asksStreaming && !asksWatch) {
            String price = convo.replaceAll("(?s).*?(\\d+[.,]\\d{2}).*", "$1").replace(',', '.');
            if (price.length() > 8) {
                price = "9.99";
            }
            return "{\"kind\":\"proposal\",\"message\":\"Here is the plan: one spec, one"
                    + " monthly price, one offering in Partner services — activation mints"
                    + " the partner code, nothing else to provision. Say the word and I"
                    + " will create it.\",\"proposal\":{"
                    + "\"specs\":[{\"ref\":\"s1\",\"name\":\"StreamPlus Service\","
                    + "\"productSpecCharacteristic\":[]}],"
                    + "\"prices\":[{\"ref\":\"p1\",\"name\":\"StreamPlus Monthly\","
                    + "\"priceType\":\"recurring\",\"recurringChargePeriodType\":\"month\","
                    + "\"price\":{\"unit\":\"EUR\",\"value\":" + price + "}}],"
                    + "\"offerings\":[{\"ref\":\"o1\",\"name\":\"StreamPlus\","
                    + "\"description\":\"All-you-can-watch streaming on your operator bill.\","
                    + "\"category\":[{\"name\":\"Partner services\"}],"
                    + "\"specRef\":\"s1\",\"priceRefs\":[\"p1\"]}]}}";
        }
        if (asksWatch) {
            return "{\"kind\":\"proposal\",\"message\":\"A kids smartwatch sells best as a"
                    + " small bundle: the watch on installments (Devices — it ships), a"
                    + " child-sized plan (Mobile plans — it gets a number and SIM), and the"
                    + " tracking add-on as optional. Shall I create all four?\",\"proposal\":{"
                    + "\"specs\":[{\"ref\":\"s1\",\"name\":\"Kids Watch\","
                    + "\"productSpecCharacteristic\":[{\"name\":\"color\",\"valueType\":\"string\","
                    + "\"productSpecCharacteristicValue\":[{\"value\":\"Rose\"},{\"value\":\"Navy\"}]}]}],"
                    + "\"prices\":[{\"ref\":\"p1\",\"name\":\"Kids Watch Installment\","
                    + "\"priceType\":\"recurring\",\"recurringChargePeriodType\":\"month\","
                    + "\"price\":{\"unit\":\"EUR\",\"value\":6.99}},"
                    + "{\"ref\":\"p2\",\"name\":\"Kids Plan Monthly\",\"priceType\":\"recurring\","
                    + "\"recurringChargePeriodType\":\"month\",\"price\":{\"unit\":\"EUR\",\"value\":4.99}},"
                    + "{\"ref\":\"p3\",\"name\":\"GPS Tracking Monthly\",\"priceType\":\"recurring\","
                    + "\"recurringChargePeriodType\":\"month\",\"price\":{\"unit\":\"EUR\",\"value\":1.99}}],"
                    + "\"offerings\":["
                    + "{\"ref\":\"o1\",\"name\":\"Kids Watch\",\"description\":\"A rugged smartwatch"
                    + " for kids, on 24-month installments.\",\"category\":[{\"name\":\"Devices\"}],"
                    + "\"specRef\":\"s1\",\"priceRefs\":[\"p1\"]},"
                    + "{\"ref\":\"o2\",\"name\":\"Kids Plan 2 GB\",\"description\":\"A small plan"
                    + " sized for a child's watch.\",\"category\":[{\"name\":\"Mobile plans\"}],"
                    + "\"priceRefs\":[\"p2\"]},"
                    + "{\"ref\":\"o3\",\"name\":\"GPS Tracking\",\"description\":\"Live location"
                    + " and safe-zone alerts.\",\"category\":[{\"name\":\"Security\"}],"
                    + "\"priceRefs\":[\"p3\"]},"
                    + "{\"ref\":\"o4\",\"name\":\"Kids Watch Starter\",\"description\":\"Watch,"
                    + " plan and optional tracking — one monthly price.\","
                    + "\"category\":[{\"name\":\"Bundles\"}],\"isBundle\":true,"
                    + "\"productOfferingTerm\":[{\"name\":\"12-month commitment\","
                    + "\"duration\":{\"amount\":12,\"units\":\"month\"}}],"
                    + "\"bundledChildren\":[{\"offeringRef\":\"o1\",\"optional\":false},"
                    + "{\"offeringRef\":\"o2\",\"optional\":false},"
                    + "{\"offeringRef\":\"o3\",\"optional\":true}]}]}}";
        }
        return "{\"kind\":\"question\",\"message\":\"Tell me about the product: is it a plan,"
                + " a device, a partner service (like streaming), or a bundle of several?"
                + " (Stub provider — configure a real model for open-ended modeling.)\","
                + "\"proposal\":null}";
    }

    @Override
    public String provider() {
        return "stub";
    }

    @Override
    public String model() {
        return "deterministic-template";
    }
}
