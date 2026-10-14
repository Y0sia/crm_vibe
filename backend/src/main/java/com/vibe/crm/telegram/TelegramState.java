package com.vibe.crm.telegram;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Служебные значения приёма апдейтов (например, offset getUpdates). */
@Entity
@Table(name = "telegram_state")
public class TelegramState {

	public static final String OFFSET = "updates_offset";

	@Id
	@Column(length = 64)
	private String key;

	@Column(nullable = false)
	private String value;

	protected TelegramState() {
	}

	public TelegramState(String key, String value) {
		this.key = key;
		this.value = value;
	}

	public String getKey() {
		return key;
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}

}
