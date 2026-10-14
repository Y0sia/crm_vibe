package com.vibe.crm;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.vibe.crm.telegram.FakeTelegramBotClient;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
	}

	/** Вместо настоящего Telegram во всех интеграционных тестах. */
	@Bean
	@Primary
	FakeTelegramBotClient fakeTelegramBotClient() {
		return new FakeTelegramBotClient();
	}

}
