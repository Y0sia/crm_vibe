package com.vibe.crm.telegram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.vibe.crm.telegram.api.TgBusinessConnection;
import com.vibe.crm.telegram.api.TgChat;
import com.vibe.crm.telegram.api.TgMessage;
import com.vibe.crm.telegram.api.TgUpdate;

/** Подменяет Telegram в тестах. */
public class FakeTelegramBotClient implements TelegramBotClient {

	public final Map<String, TgBusinessConnection> connections = new HashMap<>();

	public final List<TgUpdate> queue = new ArrayList<>();

	public final List<String> sent = new ArrayList<>();

	public final List<Long> offsets = new ArrayList<>();

	public boolean failNext;

	private final AtomicLong nextMessageId = new AtomicLong(1000);

	@Override
	public synchronized List<TgUpdate> getUpdates(long offset, int timeoutSeconds) {
		offsets.add(offset);
		if (failNext) {
			failNext = false;
			throw new TelegramApiException(502, "Bad Gateway");
		}
		List<TgUpdate> batch = List.copyOf(queue);
		queue.clear();
		return batch;
	}

	@Override
	public synchronized TgMessage sendMessage(String businessConnectionId, long chatId, String text) {
		sent.add(businessConnectionId + "|" + chatId + "|" + text);
		return new TgMessage(nextMessageId.incrementAndGet(), null, new TgChat(chatId, "private", null, null, null),
				System.currentTimeMillis() / 1000, null, businessConnectionId, null, text, null, null, null, null, null,
				null, null, null, null, null, null);
	}

	@Override
	public TgBusinessConnection getBusinessConnection(String businessConnectionId) {
		TgBusinessConnection c = connections.get(businessConnectionId);
		if (c == null) {
			throw new TelegramApiException(400, "Bad Request: business connection not found");
		}
		return c;
	}

	public void reset() {
		connections.clear();
		queue.clear();
		sent.clear();
		offsets.clear();
		failNext = false;
	}

}
