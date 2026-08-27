package com.bss.basemigration.service;

import com.bss.basemigration.exception.BadRequestException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** JSON column plumbing: the plan's matrix, eligibility, trigger and
 *  jurisdiction pack live as documents on the row. */
@Component
public class Json {

    private final ObjectMapper objectMapper;

    public Json(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BadRequestException("not serialisable");
        }
    }

    public Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    public List<Map<String, Object>> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<String> readStrings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }
}
