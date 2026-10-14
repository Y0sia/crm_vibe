package com.vibe.crm.telegram;

public class TelegramApiException extends RuntimeException {

	private final int errorCode;

	private final String description;

	public TelegramApiException(int errorCode, String description) {
		super("Telegram API " + errorCode + ": " + description);
		this.errorCode = errorCode;
		this.description = description;
	}

	public int errorCode() {
		return errorCode;
	}

	public String description() {
		return description;
	}

}
