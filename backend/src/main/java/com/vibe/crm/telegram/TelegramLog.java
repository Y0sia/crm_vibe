package com.vibe.crm.telegram;

/** Токен бота входит в адрес запросов к Telegram — в журнал он попадать не должен. */
final class TelegramLog {

	private TelegramLog() {
	}

	static String mask(String message, String token) {
		if (message == null) {
			return "null";
		}
		return token == null || token.isEmpty() ? message : message.replace(token, "***");
	}

}
