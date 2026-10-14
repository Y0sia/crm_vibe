package com.vibe.crm.telegram.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgUpdate(@JsonProperty("update_id") long updateId,
		@JsonProperty("business_connection") TgBusinessConnection businessConnection,
		@JsonProperty("business_message") TgMessage businessMessage,
		@JsonProperty("edited_business_message") TgMessage editedBusinessMessage,
		@JsonProperty("deleted_business_messages") TgBusinessMessagesDeleted deletedBusinessMessages) {
}
