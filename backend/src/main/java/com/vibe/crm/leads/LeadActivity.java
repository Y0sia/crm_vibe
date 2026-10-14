package com.vibe.crm.leads;

/** Что-то изменилось у лида — сигнал для обновления экранов (SSE). Публикуется внутри транзакции. */
public record LeadActivity(Long leadId, Kind kind) {

	public enum Kind {
		LEAD_CREATED, LEAD_UPDATED, MESSAGE_CREATED
	}

}
