package com.bss.userroles.service;

import java.security.SecureRandom;

/**
 * The passwords the BSS hands out (#321, for #320). They are read aloud by a
 * parent, printed in a cut-over letter or typed from a credentials sheet, so
 * they are four groups of four from an alphabet without look-alikes
 * (no 0/o, 1/l/i): {@code k7wq-3mzp-x9tr-c2hv}. That is 19 characters, inside
 * the 15-character minimum a NIST SP 800-63B password policy asks for, and
 * 16 symbols from 31 is about 79 bits.
 */
public final class Passwords {

    static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {
    }

    public static String generate() {
        StringBuilder out = new StringBuilder(19);
        for (int group = 0; group < 4; group++) {
            if (group > 0) {
                out.append('-');
            }
            for (int i = 0; i < 4; i++) {
                out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
        }
        return out.toString();
    }
}
