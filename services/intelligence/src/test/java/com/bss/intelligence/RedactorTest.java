package com.bss.intelligence;

import com.bss.intelligence.service.Redaction;
import com.bss.intelligence.service.Redactor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What the redactor recognises, how placeholders stay stable, and that the map reverses. */
class RedactorTest {

    private final Redactor redactor = new Redactor();

    @Test
    void masksEveryKnownTypeWithATypedPlaceholder() {
        Redaction map = redactor.begin();
        String out = redactor.redact("""
                Customer anna.svensson@example.com, phone +46 70 123 45 67, again +46 70 123 45 67.
                IBAN NO93 8601 1117 947 and account 8601.11.17947.
                ICCID 8947000000000000001, IMEI 490154203237518, card 4539 1488 0343 6467.
                fnr 01019912345, se 850101-1234, id 123456789.
                Address: Storgata 12, 0155 Oslo
                Renewal 2026-09-22 at 12:30, used 42 of 50 GB.""", map);
        assertThat(out)
                .contains("<email#1>").doesNotContain("anna.svensson")
                .contains("<phone#1>, again <phone#1>").doesNotContain("123 45 67")
                .contains("IBAN <iban#1>").doesNotContain("8601 1117 947")
                .contains("account <account#1>").doesNotContain("8601.11.17947")
                .contains("ICCID <iccid#1>").doesNotContain("8947000000000000001")
                .contains("IMEI <imei#1>").doesNotContain("490154203237518")
                .contains("card <card#1>").doesNotContain("4539 1488 0343 6467")
                .contains("fnr <nid#1>").doesNotContain("01019912345")
                .contains("se <nid#2>").doesNotContain("850101-1234")
                .contains("id <id#1>").doesNotContain("123456789")
                .contains("Address: <address#1>").doesNotContain("Storgata")
                // clocks and small numbers are not people
                .contains("2026-09-22 at 12:30, used 42 of 50 GB");
        assertThat(map.count()).isEqualTo(11);
    }

    @Test
    void aNumberThatFailsLuhnIsNotACard() {
        Redaction map = redactor.begin();
        String out = redactor.redact("ref 4539 1488 0343 6468", map);
        assertThat(map.placeholders()).noneMatch(p -> p.startsWith("<card"));
        assertThat(out).doesNotContain("<card");
    }

    @Test
    void restoreReversesTheMapEvenWhenTheModelDropsTheBrackets() {
        Redaction map = redactor.begin();
        redactor.redact("write to anna@example.com or call +47 912 34 567", map);
        String answer = "Sure — I will email <email#1> and, if needed, ring email#1's number &lt;phone#1&gt;.";
        assertThat(map.restore(answer)).isEqualTo(
                "Sure — I will email anna@example.com and, if needed, ring anna@example.com's number +47 912 34 567.");
    }

    @Test
    void theStatelessFormStillWorksForCallSites() {
        assertThat(redactor.redact("mail bob@example.org")).isEqualTo("mail <email#1>");
        assertThat(redactor.redact(null)).isNull();
    }

    @Test
    void masksLabelledPersonNamesInProseAndJson() {
        Redaction map = redactor.begin();
        String out = redactor.redact("""
                Customer: Mira Nilsen
                Contact person: Olav Fjordbygg, subscriber: Mira Nilsen
                {"givenName": "Mira", "familyName": "Nilsen", "productName": "Fiber 1000"}
                Kunde: Nils Hansen; fornavn: Nils""", map);
        assertThat(out)
                // the same person keeps the SAME placeholder wherever they recur
                .contains("Customer: <name#3>")
                .contains("subscriber: <name#3>")
                .contains("Contact person: <name#4>")
                .doesNotContain("Mira Nilsen").doesNotContain("Olav Fjordbygg")
                .doesNotContain("Nils Hansen")
                // JSON stays valid JSON: label intact, only the value replaced
                .contains("\"givenName\": \"<name#1>\"")
                .contains("\"familyName\": \"<name#2>\"")
                // a product is not a person, even inside the same object
                .contains("\"productName\": \"Fiber 1000\"");
        assertThat(map.restore(out)).contains("Mira Nilsen").contains("Olav Fjordbygg");
    }

    @Test
    void doesNotEatThingsThatAreNotPeople() {
        Redaction map = redactor.begin();
        String out = redactor.redact("""
                Product name: Fiber 1000
                Campaign name: Winter Sale
                Plan name: GenAlpha Mobile 50 GB
                {"productName": "Galaxy S26", "offeringName": "TV Max"}
                The customer asked about pricing and the contact was by phone.""", map);
        assertThat(out)
                .contains("Product name: Fiber 1000")
                .contains("Campaign name: Winter Sale")
                .contains("Plan name: GenAlpha Mobile 50 GB")
                .contains("\"productName\": \"Galaxy S26\"")
                .contains("\"offeringName\": \"TV Max\"")
                // prose that merely mentions the words is not a labelled name
                .contains("The customer asked about pricing");
        assertThat(map.count()).isZero();
    }
}
