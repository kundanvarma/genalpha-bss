package com.bss.party.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** POST .../directorySetting body: {serviceRef?, exposure?, secretNumber?}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DirectorySettingRequest(String serviceRef, String exposure, Boolean secretNumber) {
}
