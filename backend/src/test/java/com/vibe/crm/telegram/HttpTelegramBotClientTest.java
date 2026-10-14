package com.vibe.crm.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.vibe.crm.telegram.api.TgMessage;
import com.vibe.crm.telegram.api.TgUpdate;

class HttpTelegramBotClientTest {

	MockRestServiceServer server;

	HttpTelegramBotClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		client = new HttpTelegramBotClient(builder, "https://api.telegram.org", "123:ABC");
	}

	@Test
	void getUpdatesParsesBusinessMessage() {
		server.expect(requestTo("https://api.telegram.org/bot123:ABC/getUpdates"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().json("""
					{"offset":7,"timeout":25,"allowed_updates":["business_connection","business_message","edited_business_message","deleted_business_messages"]}
					"""))
			.andRespond(withSuccess("""
					{"ok":true,"result":[{"update_id":7,"business_message":{
					  "message_id":15,"business_connection_id":"bc1","date":1760359620,
					  "from":{"id":548213097,"is_bot":false,"first_name":"Дмитрий","last_name":"Орлов","username":"orlov_d"},
					  "chat":{"id":548213097,"type":"private","first_name":"Дмитрий"},
					  "photo":[{"file_id":"x","width":1,"height":1}],"caption":"Вот такая","unknown_field":1}}]}
					""", MediaType.APPLICATION_JSON));

		List<TgUpdate> updates = client.getUpdates(7, 25);

		assertThat(updates).singleElement().satisfies(u -> {
			assertThat(u.updateId()).isEqualTo(7);
			TgMessage m = u.businessMessage();
			assertThat(m.messageId()).isEqualTo(15);
			assertThat(m.businessConnectionId()).isEqualTo("bc1");
			assertThat(m.from().firstName()).isEqualTo("Дмитрий");
			assertThat(m.chat().type()).isEqualTo("private");
			assertThat(m.photo()).isNotNull();
			assertThat(m.caption()).isEqualTo("Вот такая");
			assertThat(m.sentAt().getEpochSecond()).isEqualTo(1760359620L);
		});
	}

	@Test
	void sendMessageOnBehalfOfBusinessAccount() {
		server.expect(requestTo("https://api.telegram.org/bot123:ABC/sendMessage"))
			.andExpect(content().json("""
					{"business_connection_id":"bc1","chat_id":548213097,"text":"Добрый день!"}
					"""))
			.andRespond(withSuccess("""
					{"ok":true,"result":{"message_id":16,"date":1760360520,"business_connection_id":"bc1",
					 "chat":{"id":548213097,"type":"private"},"text":"Добрый день!"}}
					""", MediaType.APPLICATION_JSON));

		TgMessage sent = client.sendMessage("bc1", 548213097L, "Добрый день!");

		assertThat(sent.messageId()).isEqualTo(16);
	}

	@Test
	void apiErrorBecomesException() {
		server.expect(requestTo("https://api.telegram.org/bot123:ABC/sendMessage"))
			.andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
				.body("{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by the user\"}"));

		assertThatThrownBy(() -> client.sendMessage("bc1", 1L, "x")).isInstanceOfSatisfying(TelegramApiException.class,
				e -> {
					assertThat(e.errorCode()).isEqualTo(403);
					assertThat(e.description()).contains("blocked");
				});
	}

}
