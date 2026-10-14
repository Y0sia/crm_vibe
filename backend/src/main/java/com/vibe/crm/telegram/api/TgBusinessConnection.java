package com.vibe.crm.telegram.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgBusinessConnection(String id, TgUser user, @JsonProperty("user_chat_id") long userChatId, long date,
		TgBusinessBotRights rights, @JsonProperty("is_enabled") boolean isEnabled) {

	public boolean canReply() {
		return rights != null && rights.canReply();
	}

}
