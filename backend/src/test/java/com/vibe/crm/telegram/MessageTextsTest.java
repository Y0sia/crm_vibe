package com.vibe.crm.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.vibe.crm.telegram.api.TgMessage;

import tools.jackson.databind.json.JsonMapper;

class MessageTextsTest {

	JsonMapper mapper = JsonMapper.builder().build();

	TgMessage msg(String json) {
		return mapper.readValue("{\"message_id\":1,\"date\":0," + json + "}", TgMessage.class);
	}

	@Test
	void plainText() {
		assertThat(MessageTexts.describe(msg("\"text\":\"Привет\""))).isEqualTo("Привет");
	}

	@Test
	void mediaWithoutCaptionIsPlaceholder() {
		assertThat(MessageTexts.describe(msg("\"photo\":[{}]"))).isEqualTo("[Фото]");
		assertThat(MessageTexts.describe(msg("\"voice\":{}"))).isEqualTo("[Голосовое сообщение]");
		assertThat(MessageTexts.describe(msg("\"video_note\":{}"))).isEqualTo("[Видеосообщение]");
		assertThat(MessageTexts.describe(msg("\"sticker\":{}"))).isEqualTo("[Стикер]");
		assertThat(MessageTexts.describe(msg("\"contact\":{\"phone_number\":\"1\"}"))).isEqualTo("[Контакт]");
	}

	@Test
	void mediaWithCaption() {
		assertThat(MessageTexts.describe(msg("\"document\":{},\"caption\":\"Договор\""))).isEqualTo("[Файл] Договор");
	}

	@Test
	void unknownContentIsAttachment() {
		assertThat(MessageTexts.describe(msg("\"poll\":{}"))).isEqualTo("[Вложение]");
	}

}
