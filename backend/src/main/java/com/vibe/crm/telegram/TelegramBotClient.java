package com.vibe.crm.telegram;

import java.util.List;

import com.vibe.crm.telegram.api.TgBusinessConnection;
import com.vibe.crm.telegram.api.TgMessage;
import com.vibe.crm.telegram.api.TgUpdate;

/** Методы Bot API, которые нужны CRM. */
public interface TelegramBotClient {

	List<TgUpdate> getUpdates(long offset, int timeoutSeconds);

	/** Отправка от имени бизнес-аккаунта. */
	TgMessage sendMessage(String businessConnectionId, long chatId, String text);

	TgBusinessConnection getBusinessConnection(String businessConnectionId);

}
