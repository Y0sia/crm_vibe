package com.vibe.crm.common;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(String message, Map<String, String> fields) {

	public ApiError(String message) {
		this(message, Map.of());
	}

}
