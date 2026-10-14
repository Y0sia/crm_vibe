package com.vibe.crm.telegram.api;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.JsonNode;

/** Сообщение Bot API. Медиа нужны только как признак наличия — поэтому JsonNode. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TgMessage(@JsonProperty("message_id") long messageId, TgUser from, TgChat chat, long date,
		@JsonProperty("edit_date") Long editDate, @JsonProperty("business_connection_id") String businessConnectionId,
		@JsonProperty("sender_business_bot") TgUser senderBusinessBot, String text, String caption, JsonNode photo,
		JsonNode video, JsonNode voice, @JsonProperty("video_note") JsonNode videoNote, JsonNode audio,
		JsonNode document, JsonNode sticker, JsonNode animation, TgContact contact, JsonNode location) {

	public Instant sentAt() {
		return Instant.ofEpochSecond(date);
	}

}
