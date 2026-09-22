package com.bss.party.privacy;

import com.bss.party.entity.Individual;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** The data passport: one JSON, a shelf per category, the caller's token doing all the reading. */
@JsonPropertyOrder({"partyId", "exportedAt", "profile", "categories", "alsoHeldUnderLegalBasis"})
public record PrivacyPassport(
        String partyId,
        String exportedAt,
        Profile profile,
        List<JsonNode> categories,
        Map<String, String> alsoHeldUnderLegalBasis) {

    /** The profile shelf: the row as held, or an honest note that there is none. */
    public sealed interface Profile permits ProfileView, NoProfile {
    }

    @JsonPropertyOrder({"id", "givenName", "familyName", "birthDate", "contactMedium"})
    public record ProfileView(String id, String givenName, String familyName, LocalDate birthDate,
            JsonNode contactMedium) implements Profile {

        public static ProfileView of(Individual person, JsonNode contactMedium) {
            return new ProfileView(person.getId(), person.getGivenName(), person.getFamilyName(),
                    person.getBirthDate(), contactMedium);
        }
    }

    public record NoProfile(String note) implements Profile {
        public static final NoProfile NONE = new NoProfile("no profile row");
    }
}
