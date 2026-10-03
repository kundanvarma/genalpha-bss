package com.bss.catalog.mapper;

import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.springframework.stereotype.Component;

/**
 * The one writer for JSON-LD that lands inside a {@code <script>} element.
 *
 * Jackson does the JSON escaping — a quote, a backslash, a newline or a control
 * character in a product name is the serialiser's problem, not ours. On top of
 * that it escapes the four characters that are safe in JSON but NOT safe inside
 * HTML: an offering honestly named {@code </script>} would otherwise end the
 * element and put the rest of the catalog into the page as markup. {@code <}
 * is the same string to a JSON parser and inert to an HTML parser, so the
 * document a crawler reads is byte-for-byte the document we meant.
 *
 * A failure to serialise is a bug in a record, never a reason to publish a
 * half-written document: it throws.
 */
@Component
public class JsonLd {

    private final ObjectWriter writer;

    public JsonLd() {
        this.writer = new ObjectMapper().writer().with(new HtmlSafe());
    }

    /** The document, ready to place between {@code <script type="application/ld+json">} tags. */
    public String write(Object document) {
        try {
            return writer.writeValueAsString(document);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("structured data would not serialise", e);
        }
    }

    /**
     * Standard JSON escaping plus {@code < > & '} as numeric escapes, so no
     * value can close the script element or open a tag of its own.
     */
    private static final class HtmlSafe extends CharacterEscapes {

        private static final long serialVersionUID = 1L;
        private static final SerializedString LT = new SerializedString("\\u003C");
        private static final SerializedString GT = new SerializedString("\\u003E");
        private static final SerializedString AMP = new SerializedString("\\u0026");
        private static final SerializedString APOS = new SerializedString("\\u0027");

        private final int[] escapes;

        private HtmlSafe() {
            int[] table = CharacterEscapes.standardAsciiEscapesForJSON();
            table['<'] = CharacterEscapes.ESCAPE_CUSTOM;
            table['>'] = CharacterEscapes.ESCAPE_CUSTOM;
            table['&'] = CharacterEscapes.ESCAPE_CUSTOM;
            table['\''] = CharacterEscapes.ESCAPE_CUSTOM;
            this.escapes = table;
        }

        @Override
        public int[] getEscapeCodesForAscii() {
            return escapes;
        }

        @Override
        public SerializableString getEscapeSequence(int ch) {
            return switch (ch) {
                case '<' -> LT;
                case '>' -> GT;
                case '&' -> AMP;
                case '\'' -> APOS;
                default -> null;
            };
        }
    }
}
