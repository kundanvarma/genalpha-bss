package com.bss.stock.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TMF687 requestedProduct: the CONFIGURED product — an offering plus the
 * characteristics that name the variant (colour, capacity). The variant match
 * is made on those, so they are typed; everything else round-trips.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "href", "name", "productCharacteristic"})
public record ProductRef(
        @JsonProperty("id") String id,
        @JsonProperty("href") String href,
        @JsonProperty("name") String name,
        @JsonProperty("productCharacteristic") List<NameValue> productCharacteristic,
        @JsonAnyGetter Map<String, Object> extensions) {

    public ProductRef {
        extensions = extensions == null ? Map.of() : new LinkedHashMap<>(extensions);
    }

    /** {name: value} of the characteristics that name the variant. */
    public Map<String, String> characteristics() {
        Map<String, String> out = new LinkedHashMap<>();
        if (productCharacteristic != null) {
            for (NameValue c : productCharacteristic) {
                if (c != null && c.name() != null) {
                    out.put(c.name(), String.valueOf(c.value()));
                }
            }
        }
        return out;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    @SuppressWarnings("unchecked")
    static ProductRef fromMap(Map<String, Object> posted) {
        if (posted == null) {
            return null;
        }
        Map<String, Object> rest = new LinkedHashMap<>(posted);
        Object id = rest.remove("id");
        Object href = rest.remove("href");
        Object name = rest.remove("name");
        Object chars = rest.remove("productCharacteristic");
        List<NameValue> characteristics = null;
        if (chars instanceof List<?> list) {
            characteristics = new ArrayList<>();
            for (Object c : list) {
                if (c instanceof Map<?, ?> m) {
                    characteristics.add(new NameValue(EntityRef.str(m.get("name")), m.get("value")));
                }
            }
        }
        return new ProductRef(EntityRef.str(id), EntityRef.str(href), EntityRef.str(name),
                characteristics, rest);
    }
}
