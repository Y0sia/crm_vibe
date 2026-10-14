package com.vibe.crm.telegram;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(TelegramProperties.class)
class TelegramConfig {

	@Bean
	TelegramBotClient telegramBotClient(TelegramProperties props) {
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
		// long polling держит запрос до 25 с — таймаут чтения с запасом
		factory.setReadTimeout(Duration.ofSeconds(40));
		return new HttpTelegramBotClient(RestClient.builder().requestFactory(factory), props.apiUrl(),
				props.botToken() == null ? "" : props.botToken());
	}

}
