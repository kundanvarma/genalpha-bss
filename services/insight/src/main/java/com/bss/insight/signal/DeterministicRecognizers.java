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
        public String type() { return "FNR"; }
        public Pattern pattern() { return P; }
        public int order() { return 10; }
    }

    /** 16-digit payment card in 4-groups (spaces or dashes). */
    @Component
    static class Card implements PiiRecognizer {
        private static final Pattern P = Pattern.compile("\\b(?:\\d{4}[ -]){3}\\d{4}\\b");
        public String type() { return "CARD"; }
        public Pattern pattern() { return P; }
        public int order() { return 20; }
    }

    @Component
    static class Email implements PiiRecognizer {
        private static final Pattern P =
                Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
        public String type() { return "EMAIL"; }
        public Pattern pattern() { return P; }
        public int order() { return 30; }
    }

    /** Norwegian 8-digit (grouped or not) and international +-prefixed numbers. */
    @Component
    static class Phone implements PiiRecognizer {
        private static final Pattern P = Pattern.compile(
                "(?:\\+\\d{1,3}[ ]?)?\\b\\d{2}[ ]?\\d{2}[ ]?\\d{2}[ ]?\\d{2}\\b|\\+\\d{8,15}\\b");
        public String type() { return "PHONE"; }
        public Pattern pattern() { return P; }
        public int order() { return 40; }
    }
}
