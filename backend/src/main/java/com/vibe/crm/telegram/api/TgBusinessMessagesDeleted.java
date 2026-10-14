package com.vibe.crm.telegram.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgBusinessMessagesDeleted(@JsonProperty("business_connection_id") String businessConnectionId,
		TgChat chat, @JsonProperty("message_ids") List<Long> messageIds) {
}
