package com.bss.communication.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The template renderer: pure. Given a template's per-locale copy and a flat,
 * dotted context ({"party.firstName": "Ada", "promotion.code": "WELCOME10"}),
 * it picks the locale (falling back to 'en' then the first available) and
 * substitutes {{token}} placeholders. Unknown tokens render empty, never as
 * a literal — a half-personalized message beats a broken one.
 */
@Component
public class TemplateRenderer {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");
    private static final TypeReference<Map<String, Map<String, String>>> LOCALES =
            new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    public TemplateRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** @return {"subject": ..., "body": ..., "locale": <the one actually used>}. */
    public Map<String, String> render(String localesJson, String locale, Map<String, Object> context) {
        Map<String, Map<String, String>> byLocale;
        try {
            byLocale = objectMapper.readValue(localesJson == null ? "{}" : localesJson, LOCALES);
        } catch (Exception e) {
            byLocale = Map.of();
        }
        Map<String, String> copy = pick(byLocale, locale);
        Map<String, String> out = new LinkedHashMap<>();
        String used = usedLocale(byLocale, locale);
        out.put("locale", used);
        out.put("subject", substitute(copy.getOrDefault("subject", ""), context));
        out.put("body", substitute(copy.getOrDefault("body", ""), context));
        return out;
    }

    private Map<String, String> pick(Map<String, Map<String, String>> byLocale, String locale) {
        if (locale != null && byLocale.containsKey(locale)) return byLocale.get(locale);
        if (byLocale.containsKey("en")) return byLocale.get("en");
        return byLocale.values().stream().findFirst().orElse(Map.of());
    }

    private String usedLocale(Map<String, Map<String, String>> byLocale, String locale) {
        if (locale != null && byLocale.containsKey(locale)) return locale;
        if (byLocale.containsKey("en")) return "en";
        return byLocale.keySet().stream().findFirst().orElse(null);
    }

    private String substitute(String text, Map<String, Object> context) {
        if (text == null) return "";
        Matcher m = TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object value = context == null ? null : context.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
