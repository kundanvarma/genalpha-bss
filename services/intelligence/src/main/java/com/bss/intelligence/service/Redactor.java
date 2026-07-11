package com.bss.intelligence.service;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Nothing personally identifying leaves the box: briefs are written by
 * marketers, but a pasted email thread or a phone number must not reach a
 * third-party model. Redaction runs on every outbound prompt, always —
 * an operator who wants raw passthrough gets it by hosting the model, not
 * by switching this off.
 */
@Component
public class Redactor {

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
    private static final Pattern PHONE = Pattern.compile("\\+?\\d[\\d\\s()-]{6,}\\d");

    public String redact(String text) {
        if (text == null) {
            return null;
        }
        return PHONE.matcher(EMAIL.matcher(text).replaceAll("[email]")).replaceAll("[phone]");
    }
}
