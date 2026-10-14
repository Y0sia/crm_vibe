package com.vibe.crm.telegram;

import com.vibe.crm.telegram.api.TgMessage;

/** Текст сообщения для CRM: медиа не храним, вместо них — пометка. */
final class MessageTexts {

	private MessageTexts() {
	}

	static String describe(TgMessage m) {
		if (m.text() != null && !m.text().isBlank()) {
			return m.text();
		}
		String label = label(m);
		String caption = m.caption() == null ? "" : m.caption().trim();
		if (label == null) {
			return caption.isEmpty() ? "[Вложение]" : caption;
		}
		return caption.isEmpty() ? label : label + " " + caption;
	}

	private static String label(TgMessage m) {
		if (m.photo() != null) return "[Фото]";
		if (m.video() != null) return "[Видео]";
		if (m.voice() != null) return "[Голосовое сообщение]";
		if (m.videoNote() != null) return "[Видеосообщение]";
		if (m.audio() != null) return "[Аудио]";
		// у GIF Telegram дублирует поле document — поэтому animation проверяется раньше
		if (m.animation() != null) return "[GIF]";
		if (m.document() != null) return "[Файл]";
		if (m.sticker() != null) return "[Стикер]";
		if (m.contact() != null) return "[Контакт]";
		if (m.location() != null) return "[Геопозиция]";
		return null;
	}

}
