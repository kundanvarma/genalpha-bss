package com.bss.entitlement.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Map;

/**
 * TS.43's XML face: the same answer as {@code text/vnd.wap.connectivity-xml}
 * — a {@code wap-provisioningdoc} with VERS, TOKEN and one APPLICATION
 * characteristic per app id (spec examples: Tables 54–69, 138). Scalars are
 * {@code parm}s, nested objects are nested characteristics, arrays repeat
 * them. The document arrives as a Jackson tree, whichever typed answer it
 * was rendered from; a null value is left off, exactly as before.
 */
public final class Ts43Xml {

    private Ts43Xml() {
    }

    public static String render(JsonNode body) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?>\n<wap-provisioningdoc version=\"1.1\">\n");
        JsonNode vers = body.get("Vers");
        if (vers != null && vers.isObject()) {
            sb.append("  <characteristic type=\"VERS\">\n");
            parms(sb, vers, "    ");
            sb.append("  </characteristic>\n");
        }
        JsonNode token = body.get("Token");
        if (token != null && token.isObject()) {
            sb.append("  <characteristic type=\"TOKEN\">\n");
            parms(sb, token, "    ");
            sb.append("  </characteristic>\n");
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = body.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getKey().startsWith("ap") || !e.getValue().isObject()) {
                continue;
            }
            sb.append("  <characteristic type=\"APPLICATION\">\n");
            sb.append("    <parm name=\"AppID\" value=\"").append(esc(e.getKey())).append("\"/>\n");
            parms(sb, e.getValue(), "    ");
            sb.append("  </characteristic>\n");
        }
        sb.append("</wap-provisioningdoc>\n");
        return sb.toString();
    }

    private static void parms(StringBuilder sb, JsonNode node, String indent) {
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> e = it.next();
            String name = e.getKey();
            JsonNode v = e.getValue();
            if (v.isObject()) {
                characteristic(sb, name, v, indent);
            } else if (v.isArray()) {
                for (JsonNode item : v) {
                    if (item.isObject()) {
                        // a list of {"X": {...}} wrappers renders as repeated characteristic X;
                        // a list of flat objects as repeated characteristic <name>
                        if (item.size() == 1 && item.elements().next().isObject()) {
                            characteristic(sb, item.fieldNames().next(), item.elements().next(), indent);
                        } else {
                            characteristic(sb, name, item, indent);
                        }
                    } else {
                        sb.append(indent).append("<parm name=\"").append(esc(name)).append("\" value=\"")
                                .append(esc(text(item))).append("\"/>\n");
                    }
                }
            } else if (!v.isNull()) {
                sb.append(indent).append("<parm name=\"").append(esc(name)).append("\" value=\"")
                        .append(esc(text(v))).append("\"/>\n");
            }
        }
    }

    private static void characteristic(StringBuilder sb, String type, JsonNode node, String indent) {
        sb.append(indent).append("<characteristic type=\"").append(esc(type)).append("\">\n");
        parms(sb, node, indent + "  ");
        sb.append(indent).append("</characteristic>\n");
    }

    private static String text(JsonNode node) {
        return node.isTextual() ? node.textValue() : node.toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
