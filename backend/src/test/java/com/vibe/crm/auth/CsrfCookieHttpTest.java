package com.vibe.crm.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import com.vibe.crm.TestcontainersConfiguration;

/**
 * Проверка на настоящем сервере: в MockMvc помощник csrf() подменяет хранилище токенов,
 * поэтому выдачу cookie XSRF-TOKEN можно проверить только так.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "crm.admin.login=admin", "crm.admin.password=admin-test-password" })
@Import(TestcontainersConfiguration.class)
class CsrfCookieHttpTest {

	@LocalServerPort
	int port;

	@Test
	void anonymousRequestGetsCsrfCookieButNoSession() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me")).build(),
					HttpResponse.BodyHandlers.ofString());

		List<String> cookies = response.headers().allValues("Set-Cookie");
		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(cookies).anyMatch(c -> c.startsWith("XSRF-TOKEN="));
		assertThat(cookies).noneMatch(c -> c.startsWith("SESSION="));
	}

}
