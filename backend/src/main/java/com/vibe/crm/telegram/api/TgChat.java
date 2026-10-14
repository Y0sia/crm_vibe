package com.vibe.crm.telegram.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgChat(long id, String type, @JsonProperty("first_name") String firstName,
		@JsonProperty("last_name") String lastName, String username) {
}
