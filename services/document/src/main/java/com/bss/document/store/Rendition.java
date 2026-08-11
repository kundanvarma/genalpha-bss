package com.bss.document.store;

/**
 * The portable rendition vocabulary — the SAME four names mean the same intent
 * across every provider, so a consumer asks for {@code hero} without knowing (or
 * caring) whose CMS/CDN serves it. Each provider maps these to its own transform
 * syntax. Unknown or absent → ORIG (no transform), never an error.
 */
public enum Rendition {

    THUMB(200),
    CARD(600),
    HERO(1200),
    ORIG(0);

    private final int width;

    Rendition(int width) {
        this.width = width;
    }

    /** Target width in px; 0 means "original, no resize". */
    public int width() {
        return width;
    }

    public boolean sized() {
        return width > 0;
    }

    public static Rendition parse(String name) {
        if (name == null || name.isBlank()) {
            return ORIG;
        }
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return ORIG;
        }
    }
}
