package com.vibe.crm.telegram.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgContact(@JsonProperty("phone_number") String phoneNumber, @JsonProperty("first_name") String firstName,
		@JsonProperty("user_id") Long userId) {
}
