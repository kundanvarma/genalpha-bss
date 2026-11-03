package com.bss.gateway.agent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough of RFC 8941 (Structured Field Values) to read and re-serialise the
 * three headers a signed agent sends: {@code Signature-Input} (a dictionary of inner
 * lists with parameters), {@code Signature} (a dictionary of byte sequences) and
 * {@code Signature-Agent} (a dictionary of strings). Strict on purpose: anything the
 * RFC calls a parse failure is an {@link IllegalArgumentException}, which the
 * verifier turns into "not signed" — never a 500.
 */
final class StructuredFields {

    private StructuredFields() {
    }

    /** A token (bare word), kept apart from a string so it re-serialises without quotes. */
    record Token(String value) {
    }

    /** A byte sequence ({@code :base64:}). */
    record Bytes(byte[] value) {
    }

    /** Ordered parameters; values are String, Token, Long, BigDecimal, Bytes or Boolean. */
    record Params(Map<String, Object> values) {
        static final Params NONE = new Params(Map.of());

        Object get(String key) {
            return values.get(key);
        }

        boolean has(String key) {
            return values.containsKey(key);
        }
    }

    sealed interface Member permits Item, InnerList {
        Params params();
    }

    record Item(Object value, Params params) implements Member {
    }

    record InnerList(List<Item> items, Params params) implements Member {
    }

    static Map<String, Member> parseDictionary(String input) {
        return new Parser(input).dictionary();
    }

    // ---------------------------------------------------------------- serialise

    static String serialize(Member member) {
        return switch (member) {
            case Item item -> serializeItem(item);
            case InnerList list -> serializeInnerList(list);
        };
    }

    static String serializeItem(Item item) {
        return bare(item.value()) + serializeParams(item.params());
    }

    static String serializeInnerList(InnerList list) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < list.items().size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(serializeItem(list.items().get(i)));
        }
        return sb.append(')').append(serializeParams(list.params())).toString();
    }

    static String serializeParams(Params params) {
        StringBuilder sb = new StringBuilder();
        params.values().forEach((k, v) -> {
            sb.append(';').append(k);
            if (!Boolean.TRUE.equals(v)) {
                sb.append('=').append(bare(v));
            }
        });
        return sb.toString();
    }

    static String bare(Object v) {
        return switch (v) {
            case String s -> quote(s);
            case Token t -> t.value();
            case Long n -> Long.toString(n);
            case BigDecimal d -> d.toPlainString();
            case Bytes b -> ":" + Base64.getEncoder().encodeToString(b.value()) + ":";
            case Boolean b -> b ? "?1" : "?0";
            default -> throw new IllegalArgumentException("not a structured value");
        };
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---------------------------------------------------------------- parse

    private static final class Parser {
        private final String in;
        private int pos;

        Parser(String in) {
            this.in = in == null ? "" : in;
        }

        Map<String, Member> dictionary() {
            Map<String, Member> out = new LinkedHashMap<>();
            skipSp();
            if (eof()) {
                return out;
            }
            while (true) {
                String key = key();
                Member member;
                if (peek() == '=') {
                    pos++;
                    member = itemOrInnerList();
                } else {
                    member = new Item(Boolean.TRUE, params());
                }
                out.put(key, member);
                skipOws();
                if (eof()) {
                    return Collections.unmodifiableMap(out);
                }
                expect(',');
                skipOws();
                if (eof()) {
                    throw fail("trailing comma");
                }
            }
        }

        private Member itemOrInnerList() {
            if (peek() != '(') {
                return new Item(bareItem(), params());
            }
            pos++;
            List<Item> items = new ArrayList<>();
            while (true) {
                skipSp();
                if (peek() == ')') {
                    pos++;
                    return new InnerList(List.copyOf(items), params());
                }
                items.add(new Item(bareItem(), params()));
                char c = peek();
                if (c != ' ' && c != ')') {
                    throw fail("inner list");
                }
            }
        }

        private Params params() {
            Map<String, Object> out = new LinkedHashMap<>();
            while (peek() == ';') {
                pos++;
                skipSp();
                String key = key();
                Object value = Boolean.TRUE;
                if (peek() == '=') {
                    pos++;
                    value = bareItem();
                }
                out.put(key, value);
            }
            return out.isEmpty() ? Params.NONE : new Params(Collections.unmodifiableMap(out));
        }

        private String key() {
            char c = peek();
            if (!(c >= 'a' && c <= 'z') && c != '*') {
                throw fail("key");
            }
            int start = pos;
            while (!eof()) {
                c = in.charAt(pos);
                if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.' || c == '*') {
                    pos++;
                } else {
                    break;
                }
            }
            return in.substring(start, pos);
        }

        private Object bareItem() {
            char c = peek();
            if (c == '-' || (c >= '0' && c <= '9')) {
                return number();
            }
            if (c == '"') {
                return string();
            }
            if (c == ':') {
                return bytes();
            }
            if (c == '?') {
                pos++;
                char b = peek();
                pos++;
                if (b == '1') {
                    return Boolean.TRUE;
                }
                if (b == '0') {
                    return Boolean.FALSE;
                }
                throw fail("boolean");
            }
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '*') {
                return token();
            }
            throw fail("item");
        }

        private Object number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            boolean decimal = false;
            while (!eof()) {
                char c = in.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' && !decimal) {
                    decimal = true;
                    pos++;
                } else {
                    break;
                }
            }
            String text = in.substring(start, pos);
            if (text.equals("-") || text.endsWith(".") || text.length() > 20) {
                throw fail("number");
            }
            if (decimal) {
                return new BigDecimal(text);
            }
            if (text.replace("-", "").length() > 15) {
                throw fail("integer too long");
            }
            return Long.parseLong(text);
        }

        private String string() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (!eof()) {
                char c = in.charAt(pos++);
                if (c == '\\') {
                    if (eof()) {
                        throw fail("escape");
                    }
                    char n = in.charAt(pos++);
                    if (n != '"' && n != '\\') {
                        throw fail("escape");
                    }
                    sb.append(n);
                } else if (c == '"') {
                    return sb.toString();
                } else if (c < 0x20 || c > 0x7e) {
                    throw fail("string character");
                } else {
                    sb.append(c);
                }
            }
            throw fail("unterminated string");
        }

        private Token token() {
            int start = pos;
            while (!eof()) {
                char c = in.charAt(pos);
                if (c > 0x20 && c < 0x7f && "\"(),;<=>?@[\\]{} ".indexOf(c) < 0 || c == ':' || c == '/') {
                    pos++;
                } else {
                    break;
                }
            }
            return new Token(in.substring(start, pos));
        }

        private Bytes bytes() {
            pos++;
            int end = in.indexOf(':', pos);
            if (end < 0) {
                throw fail("byte sequence");
            }
            String b64 = in.substring(pos, end);
            pos = end + 1;
            try {
                return new Bytes(Base64.getDecoder().decode(b64));
            } catch (IllegalArgumentException e) {
                throw fail("base64");
            }
        }

        private void expect(char c) {
            if (peek() != c) {
                throw fail("expected '" + c + "'");
            }
            pos++;
        }

        private char peek() {
            return eof() ? '\0' : in.charAt(pos);
        }

        private boolean eof() {
            return pos >= in.length();
        }

        private void skipSp() {
            while (peek() == ' ') {
                pos++;
            }
        }

        private void skipOws() {
            while (peek() == ' ' || peek() == '\t') {
                pos++;
            }
        }

        private IllegalArgumentException fail(String what) {
            return new IllegalArgumentException("structured field: " + what + " at " + pos);
        }
    }
}
