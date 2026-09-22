package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** POST clone: the sandbox's id and, optionally, its name (defaults to "<source brand> Sandbox"). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CloneRequest(String id, String name) {
}
