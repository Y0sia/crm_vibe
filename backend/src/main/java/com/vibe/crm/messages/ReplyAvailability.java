package com.vibe.crm.messages;

import java.time.Duration;
import java.time.Instant;

import com.vibe.crm.leads.Lead;
import com.vibe.crm.telegram.BusinessConnection;

/** Можно ли ответить лиду из CRM. Telegram разрешает это только в течение 24 часов после входящего. */
public final class ReplyAvailability {

	static final Duration WINDOW = Duration.ofHours(24);

	private ReplyAvailability() {
	}

	/** null — ответить можно; иначе причина, почему нельзя. */
	public static String reason(Lead lead, BusinessConnection connection, Instant now) {
		if (connection == null || !connection.isEnabled()) {
			return "Бот не подключён к аккаунту Telegram";
		}
		if (!connection.isCanReply()) {
			return "У бота нет права отвечать на сообщения — включите его в настройках Telegram для бизнеса";
		}
		if (lead.getLastIncomingAt() == null || lead.getLastIncomingAt().isBefore(now.minus(WINDOW))) {
			return "Прошло больше 24 часов с последнего сообщения лида — ответьте ему в Telegram";
		}
		return null;
	}

}
