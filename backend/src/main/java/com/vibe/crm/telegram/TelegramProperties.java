package com.vibe.crm.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crm.telegram")
public record TelegramProperties(String botToken, boolean pollingEnabled, String apiUrl) {

	public boolean configured() {
		return botToken != null && !botToken.isBlank();
	}

}
