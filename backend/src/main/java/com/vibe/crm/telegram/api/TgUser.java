package com.vibe.crm.telegram.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgUser(long id, @JsonProperty("is_bot") boolean isBot, @JsonProperty("first_name") String firstName,
		@JsonProperty("last_name") String lastName, String username) {
}
