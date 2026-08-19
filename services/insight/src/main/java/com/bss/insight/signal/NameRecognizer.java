package com.bss.insight.signal;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The deterministic NAME floor (Tvilling T-P1): a Norwegian/common first-name
 * dictionary anchors a match, and an immediately following capitalized word
 * extends it to the full name. HONEST LIMIT: dictionary recall only — a name
 * outside the list is a name that travels; the NER seam (NerRecognizer,
 * config-enabled) raises recall with a local model. This floor is still
 * deterministic, testable and free.
 */
@Component
public class NameRecognizer implements PiiRecognizer {

    private static final Set<String> FIRST_NAMES = Set.of(
            // demo personas first — the fleet must catch its own people
            "kai", "paula", "wilma", "sonny", "nils", "norah", "sigrid", "anna", "bob",
            // common Norwegian given names
            "ole", "lars", "jan", "per", "bjørn", "arne", "knut", "svein", "hans", "odd",
            "geir", "erik", "morten", "tor", "terje", "kjell", "rune", "eirik", "espen",
            "magnus", "anders", "kristian", "martin", "andreas", "fredrik", "håkon",
            "petter", "henrik", "sander", "jonas", "emil", "oskar", "mathias", "tobias",
            "anne", "inger", "kari", "marit", "ingrid", "liv", "eva", "berit", "astrid",
            "bjørg", "hilde", "gerd", "randi", "solveig", "marianne", "kristin", "mona",
            "elisabeth", "hanne", "silje", "camilla", "maria", "emma", "nora", "sofie",
            "thea", "ida", "julie", "mia", "linnea", "sara", "vilde", "selma",
            // common cross-border names support desks see
            "john", "peter", "michael", "thomas", "david", "james", "robert", "mary",
            "jennifer", "linda", "patricia", "susan", "karen", "lisa", "ada");

    /** A dictionary hit optionally followed by one or two capitalized words. */
    private static final Pattern CANDIDATE =
            Pattern.compile("\\b(\\p{Lu}\\p{Ll}+)(\\s+\\p{Lu}\\p{Ll}+){0,2}");

    @Override
    public String type() {
        return "NAME";
    }

    @Override
    public Pattern pattern() {
        return null; // dictionary recognizer — find() is the contract
    }

    @Override
    public int order() {
        return 50; // after the digit-heavy types
    }

    @Override
    public List<Span> find(String text) {
        List<Span> spans = new ArrayList<>();
        Matcher m = CANDIDATE.matcher(text);
        while (m.find()) {
            String first = m.group(1).toLowerCase(Locale.ROOT);
            if (FIRST_NAMES.contains(first)) {
                spans.add(new Span(m.start(), m.end(), m.group()));
            }
        }
        return spans;
    }
}
