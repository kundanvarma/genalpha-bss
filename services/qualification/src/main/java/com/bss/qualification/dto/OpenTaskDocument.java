package com.bss.qualification.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A TM Forum task document as the R18 kits post it — a typed envelope over an
 * open TMF body. The fields a component owns (its id, href, state, tenant and
 * the dates it stamps) cannot ride in on the body: a raw map used to carry
 * them straight into the stored document, and only the view layer's override
 * kept them from showing. Everything else the kit sends — {@code @type},
 * {@code @baseType}, operator extensions — is kept, in order, as the TMF
 * contract requires, and returned from {@link #toDocument()} after the
 * declared fields.
 */
public abstract class OpenTaskDocument {

    /** What the server stamps; a body naming them is ignored, never trusted. */
    public static final Set<String> SERVER_OWNED = Set.of(
            "id", "href", "state", "tenantId", "tenant_id",
            "serviceQualificationDate", "effectiveQualificationDate", "qualificationResult");

    private final Map<String, Object> extensions = new LinkedHashMap<>();

    @JsonAnySetter
    public void extension(String key, Object value) {
        if (!SERVER_OWNED.contains(key)) {
            extensions.put(key, value);
        }
    }

    @JsonAnyGetter
    public Map<String, Object> extensions() {
        return extensions;
    }

    /** The declared fields of the concrete document, in wire order, nulls left out. */
    protected abstract void declared(Map<String, Object> into);

    /** The document as it is stored and echoed: declared fields first, then the extensions. */
    public Map<String, Object> toDocument() {
        Map<String, Object> doc = new LinkedHashMap<>();
        declared(doc);
        doc.putAll(extensions);
        doc.values().removeIf(java.util.Objects::isNull);
        return doc;
    }

    protected static void put(Map<String, Object> into, String key, Object value) {
        if (value != null) {
            into.put(key, value);
        }
    }
}
