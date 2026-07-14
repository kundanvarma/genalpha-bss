package com.bss.insight.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TMF630 attribute selection: return only the requested fields. The id is
 * always included so results stay addressable.
 */
@Component
public class FieldSelector {

    private final ObjectMapper objectMapper;

    public FieldSelector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> select(List<?> items, String fields) {
        Set<String> keep = new LinkedHashSet<>(Arrays.asList(fields.split(",")));
        keep.add("id");
        return items.stream()
                .map(item -> objectMapper.convertValue(item, new TypeReference<LinkedHashMap<String, Object>>() {
                }))
                .map(full -> {
                    Map<String, Object> selected = new LinkedHashMap<>();
                    for (String key : keep) {
                        if (full.get(key) != null) {
                            selected.put(key, full.get(key));
                        }
                    }
                    return selected;
                })
                .toList();
    }
}
