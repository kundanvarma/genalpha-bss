package com.bss.catalog.mapper;

import com.bss.catalog.dto.ProductSpecificationDto;
import com.bss.catalog.entity.ProductSpecification;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ProductSpecificationMapper {

    private static final TypeReference<List<Map<String, Object>>> JSON_OBJECT_LIST = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public ProductSpecificationMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ProductSpecificationDto toDto(ProductSpecification entity) {
        ProductSpecificationDto dto = new ProductSpecificationDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setName(entity.getName());
        dto.setBrand(entity.getBrand());
        dto.setLifecycleStatus(entity.getLifecycleStatus());
        dto.setLastUpdate(entity.getLastUpdate());
        dto.setProductSpecCharacteristic(readJsonObjectList(entity.getProductSpecCharacteristicJson()));
        dto.setServiceSpecification(readJsonObjectList(entity.getServiceSpecificationJson()));
        dto.setType("ProductSpecification");
        return dto;
    }

    public ProductSpecification toEntity(ProductSpecificationDto dto) {
        ProductSpecification entity = new ProductSpecification();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setName(dto.getName());
        entity.setBrand(dto.getBrand());
        entity.setLifecycleStatus(dto.getLifecycleStatus());
        entity.setLastUpdate(dto.getLastUpdate());
        entity.setProductSpecCharacteristicJson(writeJsonObjectList(normalizeCharacteristics(dto.getProductSpecCharacteristic())));
        entity.setServiceSpecificationJson(writeJsonObjectList(dto.getServiceSpecification()));
        return entity;
    }

    public void applyPatch(ProductSpecificationDto patch, ProductSpecification entity) {
        if (patch.getName() != null) {
            entity.setName(patch.getName());
        }
        if (patch.getBrand() != null) {
            entity.setBrand(patch.getBrand());
        }
        if (patch.getLifecycleStatus() != null) {
            entity.setLifecycleStatus(patch.getLifecycleStatus());
        }
        if (patch.getProductSpecCharacteristic() != null) {
            entity.setProductSpecCharacteristicJson(writeJsonObjectList(normalizeCharacteristics(patch.getProductSpecCharacteristic())));
        }
        if (patch.getServiceSpecification() != null) {
            entity.setServiceSpecificationJson(writeJsonObjectList(patch.getServiceSpecification()));
        }
    }


    /**
     * A characteristic's allowed values are TMF's productSpecCharacteristicValue [{value}]. Callers (models,
     * hand-written JSON) also write "values": ["a", "b"], "allowedValues", a bare "value", or a list of strings;
     * all of those are the same intent, so they are accepted and stored in the one shape the shop and the
     * pricing conditions read. A characteristic that lists more than one value is configurable unless it
     * says otherwise; a single value is a fact.
     */
    public static List<Map<String, Object>> normalizeCharacteristics(List<Map<String, Object>> chars) {
        if (chars == null) {
            return null;
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> raw : chars) {
            if (raw == null) {
                continue;
            }
            Map<String, Object> c = new java.util.LinkedHashMap<>(raw);
            Object values = c.containsKey("productSpecCharacteristicValue") ? c.get("productSpecCharacteristicValue")
                    : c.containsKey("values") ? c.get("values")
                    : c.containsKey("allowedValues") ? c.get("allowedValues")
                    : c.get("value");
            c.remove("values");
            c.remove("allowedValues");
            c.remove("value");
            List<Map<String, Object>> norm = new java.util.ArrayList<>();
            if (values != null) {
                List<?> items = values instanceof List<?> l ? l : List.of(values);
                for (Object v : items) {
                    if (v instanceof Map<?, ?> m) {
                        Map<String, Object> mv = new java.util.LinkedHashMap<>();
                        for (Map.Entry<?, ?> e : m.entrySet()) {
                            mv.put(String.valueOf(e.getKey()), e.getValue());
                        }
                        Object val = mv.get("value") != null ? mv.get("value") : mv.get("name");
                        if (val == null) {
                            continue;
                        }
                        mv.put("value", val instanceof String ? val : String.valueOf(val));
                        norm.add(mv);
                    } else if (v != null) {
                        Map<String, Object> mv = new java.util.LinkedHashMap<>();
                        mv.put("value", String.valueOf(v));
                        norm.add(mv);
                    }
                }
            }
            c.put("productSpecCharacteristicValue", norm);
            if (c.get("configurable") == null) {
                c.put("configurable", norm.size() > 1);
            }
            out.add(c);
        }
        return out;
    }

    private String writeJsonObjectList(List<Map<String, Object>> value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable JSON array", e);
        }
    }

    private List<Map<String, Object>> readJsonObjectList(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored JSON array is unreadable", e);
        }
    }
}
