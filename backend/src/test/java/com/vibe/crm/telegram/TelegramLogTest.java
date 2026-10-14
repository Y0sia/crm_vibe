package com.vibe.crm.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TelegramLogTest {

	@Test
	void tokenIsMaskedInMessages() {
		String message = "I/O error on POST request for \"https://api.telegram.org/bot123:ABC-def/getUpdates\": timed out";
		assertThat(TelegramLog.mask(message, "123:ABC-def"))
			.isEqualTo("I/O error on POST request for \"https://api.telegram.org/bot***/getUpdates\": timed out");
	}

	@Test
	void emptyTokenOrMessageIsSafe() {
		assertThat(TelegramLog.mask("text", "")).isEqualTo("text");
		assertThat(TelegramLog.mask(null, "x")).isEqualTo("null");
	}

}
