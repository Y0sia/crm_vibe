package com.vibe.crm.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import com.vibe.crm.TestData;
import com.vibe.crm.TestcontainersConfiguration;
import com.vibe.crm.leads.LeadActivity;
import com.vibe.crm.users.Role;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "crm.admin.login=admin", "crm.admin.password=admin-test-password" })
@Import(TestcontainersConfiguration.class)
class SseHubTest {

	@LocalServerPort
	int port;

	@Autowired
	TestData data;

	@Autowired
	SseHub hub;

	@Autowired
	ApplicationEventPublisher publisher;

	@Autowired
	TransactionTemplate tx;

	@Test
	void anonymousIsRejected() throws Exception {
		HttpResponse<Void> r = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events")).build(),
				HttpResponse.BodyHandlers.discarding());
		assertThat(r.statusCode()).isEqualTo(401);
	}

	@Test
	void subscriberReceivesLeadActivityAfterCommit() throws Exception {
		data.user("sse-maria", "maria-pass", Role.MANAGER);
		HttpClient http = HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).build();
		String base = "http://localhost:" + port;
		http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/me")).build(), HttpResponse.BodyHandlers.discarding());
		String xsrf = ((java.net.CookieManager) http.cookieHandler().orElseThrow()).getCookieStore().getCookies().stream()
			.filter(c -> c.getName().equals("XSRF-TOKEN")).findFirst().orElseThrow().getValue();
		HttpResponse<String> login = http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
			.header("Content-Type", "application/json").header("X-XSRF-TOKEN", xsrf)
			.POST(HttpRequest.BodyPublishers.ofString("{\"login\":\"sse-maria\",\"password\":\"maria-pass\"}")).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(login.statusCode()).isEqualTo(200);

		int before = hub.subscribers();
		HttpResponse<java.io.InputStream> stream = http.send(
				HttpRequest.newBuilder(URI.create(base + "/api/events")).timeout(Duration.ofSeconds(10)).build(),
				HttpResponse.BodyHandlers.ofInputStream());
		assertThat(stream.statusCode()).isEqualTo(200);
		long deadline = System.currentTimeMillis() + 5000;
		while (hub.subscribers() == before && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
		}

		CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> {
			try (BufferedReader r = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
				String l;
				while ((l = r.readLine()) != null) {
					if (l.startsWith("data:")) {
						return l;
					}
				}
				return null;
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		tx.executeWithoutResult(t -> publisher.publishEvent(new LeadActivity(42L, LeadActivity.Kind.LEAD_UPDATED)));

		assertThat(line.get(5, TimeUnit.SECONDS)).contains("\"leadId\":42").contains("LEAD_UPDATED");
	}

}
