package com.bss.campaign.service;

import com.bss.campaign.client.CommunicationClient.AiMark;
import com.bss.campaign.exception.BadRequestException;
import com.bss.campaign.security.TenantScope;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The AI mark is decided by the server (#422 review, finding 10): copy that
 * arrives with a receipt intelligence signed ({@code aiDraft}) is marked with
 * the model the receipt names, whatever the body says about {@code aiGenerated};
 * a receipt that does not verify is refused; copy with no receipt keeps the
 * declared mark, and copy with neither is human. And the mark follows the text
 * actually sent (finding 11): an A/B arm that replaces only the subject still
 * sends the model's body, so the send stays marked.
 */
public class AiProvenanceTest {

    /** Pinned in intelligence's {@code AiDraftProvenanceTest} and computed outside Java. */
    public static final String VECTOR = "aid1.eyJ0ZW5hbnQiOiJnZW5hbHBoYSIsIm1vZGVsIjoiYW50aHJvcGljL2NsYXVkZS1zb25uZXQtNCIs"
            + "ImRyYWZ0IjoiZHJhZnQtMSIsImlzc3VlZEF0IjoiMjAyNi0xMS0wM1QxMDowMDowMFoifQ."
            + "mn0XNb3gS8VIBsPAVQfMQgsdU4q43KmBkRAlu03l138";

    private static AiProvenance provenance(String key, String tenant) {
        TenantScope scope = mock(TenantScope.class);
        when(scope.currentTenantId()).thenReturn(tenant);
        return new AiProvenance(key, scope);
    }

    @Test
    void aReceiptFromThisOperatorMarksTheCopyWhateverTheBodySays() {
        AiProvenance p = provenance("test-provenance-key", "genalpha");
        assertEquals(new AiMark("anthropic/claude-sonnet-4"), p.markFor(VECTOR, false, "typed by me"));
        assertEquals(new AiMark("anthropic/claude-sonnet-4"), p.markFor(VECTOR, null, null));
    }

    @Test
    void aReceiptThatDoesNotVerifyIsRefused() {
        AiProvenance p = provenance("test-provenance-key", "genalpha");
        // another operator's receipt
        assertThrows(BadRequestException.class, () -> provenance("test-provenance-key", "tenant-a").markFor(VECTOR, true, null));
        // signed under another key
        assertThrows(BadRequestException.class, () -> provenance("another-key", "genalpha").markFor(VECTOR, true, null));
        // a payload edited after signing (model swapped), the signature kept
        String[] parts = VECTOR.split("\\.");
        String edited = "aid1." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"tenant\":\"genalpha\",\"model\":\"human\",\"draft\":\"draft-1\",\"issuedAt\":\"2026-11-03T10:00:00Z\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "." + parts[2];
        assertThrows(BadRequestException.class, () -> p.markFor(edited, true, null));
        assertThrows(BadRequestException.class, () -> p.markFor("not-a-receipt", true, null));
        assertThrows(BadRequestException.class, () -> p.markFor("aid1.%%%.x", true, null));
    }

    @Test
    void withNoReceiptTheDeclarationStandsAndNothingDeclaredIsHuman() {
        AiProvenance p = provenance("test-provenance-key", "genalpha");
        assertEquals(new AiMark("openai/gpt-5"), p.markFor(null, true, "openai/gpt-5"));
        assertEquals(new AiMark("openai/gpt-5"), p.markFor(" ", null, "openai/gpt-5"));
        assertNull(p.markFor(null, false, null));
        assertNull(p.markFor(null, null, ""));
    }

    @Test
    void withNoKeyAReceiptCannotBeCheckedSoItOnlyEverAddsAMark() {
        AiProvenance off = provenance("", "genalpha");
        // cannot be verified: still marked (the safe direction), model as declared
        assertEquals(new AiMark("openai/gpt-5"), off.markFor(VECTOR, false, "openai/gpt-5"));
        assertEquals(new AiMark(null), off.markFor("anything", false, null));
    }

    @Test
    void stampingAJourneyMarksEveryPieceOfCopyThatCarriesAReceipt() {
        AiProvenance p = provenance("test-provenance-key", "genalpha");
        Map<String, Object> message = copy("message", VECTOR);
        message.put("aiGenerated", false);
        Map<String, Object> decision = new LinkedHashMap<>(Map.of("type", "decision", "inSegment", "Gamers"));
        decision.put("then", copy(null, VECTOR));
        decision.put("else", copy(null, null));
        Map<String, Object> timeout = new LinkedHashMap<>(Map.of("type", "waitForEvent", "event", "X"));
        timeout.put("onTimeout", copy(null, VECTOR));
        Map<String, Object> human = copy("message", null);
        p.stampSteps(List.of(message, decision, timeout, human));

        assertEquals(new AiMark("anthropic/claude-sonnet-4"), JourneyService.aiMarkOf(message));
        assertEquals(new AiMark("anthropic/claude-sonnet-4"), JourneyService.aiMarkOf(castMap(decision.get("then"))));
        assertEquals(new AiMark("anthropic/claude-sonnet-4"), JourneyService.aiMarkOf(castMap(timeout.get("onTimeout"))));
        assertNull(JourneyService.aiMarkOf(castMap(decision.get("else"))));
        assertNull(JourneyService.aiMarkOf(human));
    }

    @Test
    void aStoredStepWithAReceiptIsMarkedAtSendEvenIfTheFlagWasCleared() {
        Map<String, Object> step = copy("message", VECTOR);
        step.put("aiGenerated", false);
        assertEquals(new AiMark(null), JourneyService.aiMarkOf(step));
    }

    /** Finding 11: the mark follows the text that is actually sent. */
    @Test
    void aSubjectOnlyArmOverAnAiStepStillSendsTheModelsBodySoItStaysMarked() {
        Map<String, Object> step = copy("message", null);
        step.put("aiGenerated", true);
        step.put("aiModel", "openai/gpt-5");
        Map<String, Object> spoken = AiProvenance.spokenInArm(step, Map.of("name", "B", "subject", "Typed by a person"));
        assertEquals("Typed by a person", spoken.get("subject"));
        assertEquals("Model body", spoken.get("content"));
        assertEquals(new AiMark("openai/gpt-5"), JourneyService.aiMarkOf(spoken));
    }

    @Test
    void anArmThatReplacesAllTheCopySpeaksWithItsOwnProvenance() {
        Map<String, Object> aiStep = copy("message", null);
        aiStep.put("aiGenerated", true);
        aiStep.put("aiModel", "openai/gpt-5");
        // a person wrote the whole arm: nothing the model wrote is sent
        assertNull(JourneyService.aiMarkOf(AiProvenance.spokenInArm(aiStep,
                Map.of("name", "B", "subject", "Mine", "content", "All mine"))));
        // a model wrote the arm over a human step: the arm's mark
        Map<String, Object> humanStep = copy("message", null);
        assertEquals(new AiMark("anthropic/x"), JourneyService.aiMarkOf(AiProvenance.spokenInArm(humanStep,
                Map.of("name", "A", "subject", "s", "content", "c", "aiGenerated", true, "aiModel", "anthropic/x"))));
        // a person's content-only arm over a human step: human
        assertNull(JourneyService.aiMarkOf(AiProvenance.spokenInArm(humanStep, Map.of("name", "C", "content", "c"))));
        // a marked subject-only arm over a human step: the model's subject is sent
        assertEquals(new AiMark("anthropic/x"), JourneyService.aiMarkOf(AiProvenance.spokenInArm(humanStep,
                Map.of("name", "D", "subject", "s", "aiGenerated", true, "aiModel", "anthropic/x"))));
    }

    private static Map<String, Object> copy(String type, String aiDraft) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (type != null) {
            m.put("type", type);
        }
        m.put("subject", "Model subject");
        m.put("content", "Model body");
        if (aiDraft != null) {
            m.put("aiDraft", aiDraft);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }
}
