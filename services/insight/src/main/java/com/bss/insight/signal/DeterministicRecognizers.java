package com.bss.insight.signal;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * The deterministic floor of the PII firewall — Norwegian + English patterns
 * that must never depend on a model: fødselsnummer, payment card, email,
 * phone. Order matters: the digit-heavy types run before the looser ones so
 * an fnr is [FNR], not two halves of a [PHONE].
 */
public final class DeterministicRecognizers {

    private DeterministicRecognizers() {
    }

    /** 11-digit fødselsnummer, optional space after the birth date. */
    @Component
    static class Fnr implements PiiRecognizer {
        private static final Pattern P = Pattern.compile("\\b\\d{6}[ ]?\\d{5}\\b");
        @Override
        public String type() { return "FNR"; }
        @Override
        public Pattern pattern() { return P; }
        @Override
        public int order() { return 10; }
    }

    /** 16-digit payment card in 4-groups (spaces or dashes). */
    @Component
    static class Card implements PiiRecognizer {
        private static final Pattern P = Pattern.compile("\\b(?:\\d{4}[ -]){3}\\d{4}\\b");
        @Override
        public String type() { return "CARD"; }
        @Override
        public Pattern pattern() { return P; }
        @Override
        public int order() { return 20; }
    }

    @Component
    static class Email implements PiiRecognizer {
        // BOUNDED, BECAUSE THE UNBOUNDED FORM WAS QUADRATIC. An unbounded greedy
        // run with no word boundary in front of it: on text that never reaches
        // an '@' the engine consumed the run, failed, retried from the next
        // character, and consumed it again — from every position. Measured on
        // this pattern, on a signal payload of nothing but letters:
        //     10 KB -> 208 ms,  40 KB -> 4.4 s,  80 KB -> 18.4 s
        // This is the PII firewall, so the text it scans is whatever a
        // connector ingested. The bounds are RFC 5321's (64 of local part, 63
        // of a domain label, and a sane cap on the TLD); real addresses are far
        // inside them, so matches are unchanged and 80 KB now costs 23 ms.
        // CodeQL did NOT flag this one — the twin in intelligence/Redactor.java
        // it did flag was the SHORTER stall of the two, at 10.8 s.
        private static final Pattern P =
                Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,63}\\.[A-Za-z]{2,24}");
        @Override
        public String type() { return "EMAIL"; }
        @Override
        public Pattern pattern() { return P; }
        @Override
        public int order() { return 30; }
    }

    /** Norwegian 8-digit (grouped or not) and international +-prefixed numbers. */
    @Component
    static class Phone implements PiiRecognizer {
        private static final Pattern P = Pattern.compile(
                "(?:\\+\\d{1,3}[ ]?)?\\b\\d{2}[ ]?\\d{2}[ ]?\\d{2}[ ]?\\d{2}\\b|\\+\\d{8,15}\\b");
        @Override
        public String type() { return "PHONE"; }
        @Override
        public Pattern pattern() { return P; }
        @Override
        public int order() { return 40; }
    }
}
