package com.bss.entitlement.service;

import java.util.List;
import java.util.Map;

/**
 * TS.43's XML face: the same answer as {@code text/vnd.wap.connectivity-xml}
 * — a {@code wap-provisioningdoc} with VERS, TOKEN and one APPLICATION
 * characteristic per app id (spec examples: Tables 54–69, 138). Scalars are
 * {@code parm}s, nested maps are nested characteristics, lists repeat them.
 */
public final class Ts43Xml {

    private Ts43Xml() {
    }

    public static String render(Map<String, Object> body) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?>\n<wap-provisioningdoc version=\"1.1\">\n");
        Object vers = body.get("Vers");
        if (vers instanceof Map<?, ?> v) {
            sb.append("  <characteristic type=\"VERS\">\n");
            parms(sb, v, "    ");
            sb.append("  </characteristic>\n");
        }
        Object token = body.get("Token");
        if (token instanceof Map<?, ?> t) {
            sb.append("  <characteristic type=\"TOKEN\">\n");
            parms(sb, t, "    ");
            sb.append("  </characteristic>\n");
        }
        for (Map.Entry<String, Object> e : body.entrySet()) {
            if (!e.getKey().startsWith("ap") || !(e.getValue() instanceof Map<?, ?> app)) {
                continue;
            }
            sb.append("  <characteristic type=\"APPLICATION\">\n");
            sb.append("    <parm name=\"AppID\" value=\"").append(esc(e.getKey())).append("\"/>\n");
            parms(sb, app, "    ");
            sb.append("  </characteristic>\n");
        }
        sb.append("</wap-provisioningdoc>\n");
        return sb.toString();
    }

    private static void parms(StringBuilder sb, Map<?, ?> m, String indent) {
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String name = String.valueOf(e.getKey());
            Object v = e.getValue();
            if (v instanceof Map<?, ?> inner) {
                characteristic(sb, name, inner, indent);
            } else if (v instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> im) {
                        // a list of {"X": {...}} wrappers renders as repeated characteristic X;
                        // a list of flat maps as repeated characteristic <name>
                        if (im.size() == 1 && im.values().iterator().next() instanceof Map<?, ?> wrapped) {
                            characteristic(sb, String.valueOf(im.keySet().iterator().next()), wrapped, indent);
                        } else {
                            characteristic(sb, name, im, indent);
                        }
                    } else {
                        sb.append(indent).append("<parm name=\"").append(esc(name)).append("\" value=\"")
                                .append(esc(String.valueOf(item))).append("\"/>\n");
                    }
                }
            } else if (v != null) {
                sb.append(indent).append("<parm name=\"").append(esc(name)).append("\" value=\"")
                        .append(esc(String.valueOf(v))).append("\"/>\n");
            }
        }
    }

    private static void characteristic(StringBuilder sb, String type, Map<?, ?> m, String indent) {
        sb.append(indent).append("<characteristic type=\"").append(esc(type)).append("\">\n");
        parms(sb, m, indent + "  ");
        sb.append(indent).append("</characteristic>\n");
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
