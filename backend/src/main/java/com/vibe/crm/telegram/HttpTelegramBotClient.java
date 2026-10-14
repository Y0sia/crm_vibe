package com.vibe.crm.telegram;

import java.util.List;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.vibe.crm.telegram.api.TgBusinessConnection;
import com.vibe.crm.telegram.api.TgMessage;
import com.vibe.crm.telegram.api.TgResponse;
import com.vibe.crm.telegram.api.TgUpdate;

public class HttpTelegramBotClient implements TelegramBotClient {

	static final List<String> ALLOWED_UPDATES = List.of("business_connection", "business_message",
			"edited_business_message", "deleted_business_messages");

	private final RestClient rest;

	public HttpTelegramBotClient(RestClient.Builder builder, String apiUrl, String token) {
		this.rest = builder.baseUrl(apiUrl + "/bot" + token)
			.defaultStatusHandler(status -> true, (request, response) -> {
				// Тело ошибки Telegram — тот же JSON {ok:false,...}; разбираем его ниже как обычный ответ.
			})
			.build();
	}

	@Override
	public List<TgUpdate> getUpdates(long offset, int timeoutSeconds) {
		return call("/getUpdates", Map.of("offset", offset, "timeout", timeoutSeconds, "allowed_updates", ALLOWED_UPDATES),
				new ParameterizedTypeReference<TgResponse<List<TgUpdate>>>() {
				});
	}

	@Override
	public TgMessage sendMessage(String businessConnectionId, long chatId, String text) {
		return call("/sendMessage", Map.of("business_connection_id", businessConnectionId, "chat_id", chatId, "text", text),
				new ParameterizedTypeReference<TgResponse<TgMessage>>() {
				});
	}

	@Override
	public TgBusinessConnection getBusinessConnection(String businessConnectionId) {
		return call("/getBusinessConnection", Map.of("business_connection_id", businessConnectionId),
				new ParameterizedTypeReference<TgResponse<TgBusinessConnection>>() {
				});
	}

	private <T> T call(String method, Map<String, Object> body, ParameterizedTypeReference<TgResponse<T>> type) {
		TgResponse<T> response = rest.post().uri(method).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(type);
		if (response == null) {
			throw new TelegramApiException(0, "пустой ответ");
		}
		if (!response.ok()) {
			throw new TelegramApiException(response.errorCode() == null ? 0 : response.errorCode(),
					response.description());
		}
		return response.result();
	}

}
