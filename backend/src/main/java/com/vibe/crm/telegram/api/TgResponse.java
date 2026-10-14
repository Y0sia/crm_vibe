package com.vibe.crm.telegram.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgResponse<T>(boolean ok, T result, @JsonProperty("error_code") Integer errorCode, String description) {
}
