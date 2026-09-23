package com.bss.qualification.dto;

import java.util.Map;

/** The criteria a query was answered for, echoed back as asked. */
public record SearchCriteria(Map<String, Object> place) {
}
