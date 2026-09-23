package com.bss.intelligence.service;

import org.springframework.stereotype.Component;

import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nothing personally identifying leaves the box. Every outbound prompt goes
 * through here (the governor applies it before the provider sees a byte,
 * unless the tenant opted into raw exposure); the ledger keeps the redacted
 * copy always. Each value becomes a typed, stable placeholder
 * ({@code <email#1>}, {@code <iban#1>} …) recorded in a {@link Redaction}
 * so the caller can put the real value back into the model's answer.
 *
 * Recognised: email; IBAN (mod-97 checked); Norwegian bank account
 * (dddd.dd.ddddd); ICCID (19–20 digits); IMEI (15 digits); card PAN (13–19
 * digits, Luhn); national identity numbers (Norwegian fødselsnummer, 11
 * digits; Nordic dddddd-dddd); generic 9–12 digit identifiers; phone
 * numbers; the value of a labelled address line ("Address: …"); and the value
 * of a labelled PERSON name, in prose ("Customer: …") or JSON ("givenName").
 * A name in free prose with no label still goes — stated in docs/privacy.md.
 */
@Component
public class Redactor {

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
    private static final Pattern ADDRESS_LINE = Pattern.compile(
            "(?im)(?<![\\w])((?:(?:street|billing|shipping|postal|home|invoice|delivery|installation|service)[ \\t]+)?"
            + "(?:address|adresse|gateadresse|postadresse|street|strasse|straße|osoite)[ \\t]*[:=][ \\t]*)([^\\r\\n]+?)[ \\t]*$");
    /**
     * A LABELLED person name, in prose ({@code Customer: Mira Nilsen}) or in
     * embedded JSON ({@code "familyName": "Nilsen"}) — the two shapes a prompt
     * assembled from BSS data actually carries.
     *
     * Only PERSON labels, never a bare "name". "Product name: Fiber 1000" and
     * "Campaign name: Winter" are not people, and a recogniser that eats them
     * would make every prompt worse while protecting nobody. The price of that
     * precision is stated in docs/privacy.md: a name in free prose, with no
     * label in front of it, still goes.
     */
    private static final String NAME_LABELS =
            "customer|customer[ _]?name|subscriber|subscriber[ _]?name|contact|contact[ _]?name"
            + "|contact[ _]?person|account[ _]?holder|party[ _]?name|full[ _]?name"
            + "|given[ _]?name|first[ _]?name|family[ _]?name|last[ _]?name|middle[ _]?name"
            + "|kunde|kundenavn|kontaktperson|kontaktnavn|abonnent|fornavn|etternavn";
    private static final Pattern NAME_LINE = Pattern.compile(
            "(?im)(?<![\\w])((?:" + NAME_LABELS + ")[ \\t]*[:=][ \\t]*)([^\\r\\n,;{}\\[\\]\"]+?)[ \\t]*(?=[,;]|$)");
    private static final Pattern NAME_JSON = Pattern.compile(
            "(?i)(\"(?:" + NAME_LABELS + ")\"[ \\t]*:[ \\t]*\")([^\"]+)(\")");
    private static final Pattern IBAN = Pattern.compile(
            "\\b[A-Z]{2}\\d{2}(?:[ ]?[A-Z0-9]{4}){2,7}(?:[ ]?[A-Z0-9]{1,4})?\\b");
    private static final Pattern NO_ACCOUNT = Pattern.compile("\\b\\d{4}[ .]\\d{2}[ .]\\d{5}\\b");
    private static final Pattern ICCID = Pattern.compile("\\b\\d{19,20}\\b");
    private static final Pattern IMEI = Pattern.compile("\\b\\d{15}\\b");
    private static final Pattern CARD = Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b");
    private static final Pattern NID = Pattern.compile("\\b(?:\\d{11}|\\d{6}[-+]\\d{4})\\b");
    private static final Pattern GENERIC_ID = Pattern.compile("\\b\\d{9,12}\\b");
    private static final Pattern PHONE = Pattern.compile("(?<![\\w.])(?:\\+|00)?\\d(?:[\\d\\s().-]{5,}\\d)(?![\\w])");
    private static final Pattern DATE_LIKE = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}$|^\\d{2}[-/.]\\d{2}[-/.]\\d{4}$|^\\d{4}-\\d{2}-\\d{2}[ ]\\d{2}");

    /** Stateless convenience: redact with a throw-away map (no reversal). */
    public String redact(String text) {
        return redact(text, new Redaction());
    }

    /** Start a per-call map; pass it to every {@link #redact(String, Redaction)}
     * of the same call so system and user prompt share placeholders. */
    public Redaction begin() {
        return new Redaction();
    }

    public String redact(String text, Redaction map) {
        if (text == null) {
            return null;
        }
        String out = replaceAll(text, EMAIL, "email", map, v -> true);
        // names before the numeric recognisers: a name is captured by its
        // label, and the label tells us more than any shape ever could
        out = redactLabelled(out, NAME_JSON, "name", map, 2);
        out = redactLabelled(out, NAME_LINE, "name", map, 2);
        out = redactAddressLines(out, map);
        out = replaceAll(out, IBAN, "iban", map, Redactor::ibanChecks);
        out = replaceAll(out, NO_ACCOUNT, "account", map, v -> true);
        out = replaceAll(out, ICCID, "iccid", map, v -> true);
        out = replaceAll(out, IMEI, "imei", map, v -> true);
        out = replaceAll(out, CARD, "card", map, v -> luhn(digits(v)));
        out = replaceAll(out, NID, "nid", map, v -> true);
        out = replaceAll(out, GENERIC_ID, "id", map, v -> true);
        out = replaceAll(out, PHONE, "phone", map, Redactor::phoneLike);
        return out;
    }

    private static String replaceAll(String text, Pattern pattern, String type, Redaction map,
            Predicate<String> accept) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(
                    accept.test(value) ? map.placeholder(type, value) : value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Replace only the VALUE group of a labelled match, keeping the label and
     * anything after it intact — so {@code "familyName": "Nilsen"} stays valid
     * JSON with a placeholder inside it, and the model still knows what the
     * field meant.
     */
    private static String redactLabelled(String text, Pattern pattern, String type,
            Redaction map, int valueGroup) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = m.group(valueGroup).trim();
            if (value.isEmpty() || value.startsWith("<")) {
                continue;   // already a placeholder, or nothing to hide
            }
            StringBuilder replacement = new StringBuilder(m.group(1));
            replacement.append(map.placeholder(type, value));
            for (int g = valueGroup + 1; g <= m.groupCount(); g++) {
                if (m.group(g) != null) {
                    replacement.append(m.group(g));
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement.toString()));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String redactAddressLines(String text, Redaction map) {
        Matcher m = ADDRESS_LINE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(
                    m.group(1) + map.placeholder("address", m.group(2))));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static boolean phoneLike(String value) {
        String trimmed = value.trim();
        if (DATE_LIKE.matcher(trimmed).find()) {
            return false;
        }
        int n = digits(trimmed).length();
        boolean international = trimmed.startsWith("+") || trimmed.startsWith("00");
        return international ? n >= 7 && n <= 15 : n >= 8 && n <= 15;
    }

    private static String digits(String s) {
        return s.replaceAll("\\D", "");
    }

    /** ISO 13616 mod-97: rearrange, letters → numbers, remainder must be 1. */
    private static boolean ibanChecks(String candidate) {
        String iban = candidate.replace(" ", "");
        if (iban.length() < 15 || iban.length() > 34) {
            return false;
        }
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        int remainder = 0;
        for (char c : rearranged.toCharArray()) {
            int value = Character.isDigit(c) ? c - '0' : Character.toUpperCase(c) - 'A' + 10;
            if (value < 0 || value > 35) {
                return false;
            }
            remainder = value >= 10 ? (remainder * 100 + value) % 97 : (remainder * 10 + value) % 97;
        }
        return remainder == 1;
    }

    /** Luhn check over a digit string. */
    static boolean luhn(String digits) {
        int sum = 0;
        boolean twice = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (twice) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            twice = !twice;
        }
        return digits.length() >= 12 && sum % 10 == 0;
    }
}
