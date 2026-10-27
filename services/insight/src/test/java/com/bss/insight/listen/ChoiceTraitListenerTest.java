package com.bss.insight.listen;

import com.bss.insight.service.PartyTraitService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Benefit events as traits (#361): the household's benefits, and its Choices left — newest wins. */
class ChoiceTraitListenerTest {

    private final PartyTraitService traits = mock(PartyTraitService.class);
    private final ChoiceTraitListener listener = new ChoiceTraitListener(traits, JsonMapper.builder().build());

    private static Map<String, Object> allocation(String name, int left) {
        return Map.of("choiceAllocation", Map.of("offeringName", name, "choicesLeft", left,
                "relatedParty", List.of(Map.of("id", "cust-1", "role", "customer"))));
    }

    @Test
    void aPickAddsTheBenefit_andTheCountFollows() {
        when(traits.valueOf("cust-1", "choicesAt")).thenReturn(Optional.empty());
        listener.apply("ChoicePickedEvent", "2026-10-27T20:00:00Z", allocation("StreamCo Standard", 1));
        verify(traits).upsert("cust-1", "benefit", "StreamCo Standard");
        verify(traits).setTrait("cust-1", "choicesLeft", "1");
    }

    @Test
    void aReleaseRemovesTheBenefit() {
        when(traits.valueOf("cust-1", "choicesAt")).thenReturn(Optional.empty());
        listener.apply("ChoiceReleasedEvent", "2026-10-27T20:00:00Z", allocation("StreamCo Standard", 2));
        verify(traits).remove("cust-1", "benefit", "StreamCo Standard");
        verify(traits).setTrait("cust-1", "choicesLeft", "2");
    }

    @Test
    void anOlderEventArrivingLate_doesNotOverwriteTheCount() {
        when(traits.valueOf("cust-1", "choicesAt")).thenReturn(Optional.of("2026-10-27T21:00:00Z"));
        listener.apply("ChoiceHeldEvent", "2026-10-27T20:00:00Z", allocation("StreamCo Standard", 0));
        verify(traits, never()).setTrait(eq("cust-1"), eq("choicesLeft"), anyString());
    }

    @Test
    void anEventNamingNoHousehold_changesNothing() {
        listener.apply("ChoiceCostRecordedEvent", "2026-10-27T20:00:00Z", Map.of("partnerCost", Map.of("amount", 4)));
        verify(traits, never()).setTrait(anyString(), anyString(), anyString());
        verify(traits, never()).upsert(anyString(), anyString(), any());
    }
}
