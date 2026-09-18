package com.bss.catalog.mapper;

import com.bss.catalog.dto.ServiceCatalogJobDto;
import com.bss.catalog.entity.ServiceCatalogJob;
import org.springframework.stereotype.Component;

@Component
public class ServiceCatalogJobMapper {

    /** The TM Forum Open API and Data Model home of the two job schemas (shared across the catalog APIs). */
    private static final String SCHEMA_BASE =
            "https://raw.githubusercontent.com/tmforum-apis/Open_Api_And_Data_Model/master/schemas/Common/";

    public ServiceCatalogJobDto toDto(ServiceCatalogJob entity) {
        ServiceCatalogJobDto dto = new ServiceCatalogJobDto();
        dto.setId(entity.getId());
        dto.setHref(entity.getHref());
        dto.setUrl(entity.getUrl());
        dto.setPath(entity.getPath());
        dto.setContentType(entity.getContentType());
        dto.setQuery(entity.getQuery());
        dto.setStatus(entity.getStatus());
        dto.setErrorLog(entity.getErrorLog());
        dto.setCreationDate(entity.getCreationDate());
        dto.setCompletionDate(entity.getCompletionDate());
        String type = ServiceCatalogJob.EXPORT.equals(entity.getKind()) ? "ExportJob" : "ImportJob";
        dto.setType(type);
        dto.setSchemaLocation(SCHEMA_BASE + type + ".schema.json");
        return dto;
    }

    public ServiceCatalogJob toEntity(ServiceCatalogJobDto dto) {
        ServiceCatalogJob entity = new ServiceCatalogJob();
        entity.setId(dto.getId());
        entity.setHref(dto.getHref());
        entity.setUrl(dto.getUrl());
        entity.setPath(dto.getPath());
        entity.setContentType(dto.getContentType());
        entity.setQuery(dto.getQuery());
        entity.setStatus(dto.getStatus());
        entity.setErrorLog(dto.getErrorLog());
        entity.setCreationDate(dto.getCreationDate());
        entity.setCompletionDate(dto.getCompletionDate());
        return entity;
    }
}
