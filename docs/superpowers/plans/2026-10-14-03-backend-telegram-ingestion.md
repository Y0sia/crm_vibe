# Этап 3. Бекенд: приём сообщений из Telegram — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Бот-секретарь получает апдейты Telegram Business, и каждый, кто пишет в личку аккаунту, становится лидом; переписка, правки, удаления, телефон из контакта и время ответа сохраняются.

**Architecture:** Пакет `com.vibe.crm.telegram`: DTO Bot API (records), клиент на `RestClient` за интерфейсом `TelegramBotClient`, `UpdateProcessor` (одна транзакция на апдейт, вся бизнес-логика), `UpdatePoller` (фоновый long polling, offset в БД). Пакет `com.vibe.crm.messages`: сообщения и `ResponseTracker` (время ответа). После изменений публикуется `LeadActivity` (Spring event) — его подхватит SSE на этапе 4.

**Tech Stack:** Spring Boot 4.1.1, `RestClient` + `JdkClientHttpRequestFactory`, Jackson 3, `MockRestServiceServer`, Testcontainers.

Спека: `docs/superpowers/specs/2026-10-14-telegram-leads-crm-design.md` (разделы «Ограничения Telegram Business», «Обработка входящих апдейтов», «Время ответа»).

## Global Constraints

- Всё из Global Constraints этапов 1–2.
- Jackson 3: `tools.jackson.databind.*` (`JsonMapper`, `JsonNode`), аннотации — `com.fasterxml.jackson.annotation.*`.
- Свойства: `crm.telegram.bot-token: ${TELEGRAM_BOT_TOKEN:}`, `crm.telegram.polling-enabled: true`, `crm.telegram.api-url: https://api.telegram.org`. **В тестах приём выключен** (`src/test/resources/application.properties`) — тесты никогда не ходят в настоящий Telegram и не забирают реальные апдейты.
- `allowed_updates`: `business_connection`, `business_message`, `edited_business_message`, `deleted_business_messages`. Long polling `timeout=25` с.
- Обрабатываются только чаты `type = "private"`.
- Пометки медиа (если нет текста/подписи — только пометка; если есть подпись — «пометка подпись»): `photo` «[Фото]», `video` «[Видео]», `voice` «[Голосовое сообщение]», `video_note` «[Видеосообщение]», `audio` «[Аудио]», `document` «[Файл]», `sticker` «[Стикер]», `animation` «[GIF]», `contact` «[Контакт]», `location` «[Геопозиция]», иначе «[Вложение]».
- Сообщения, отправленные нашим ботом от имени аккаунта (`sender_business_bot` не пуст), процессор пропускает — их сохраняет отправка из CRM (этап 4), иначе ответ посчитается дважды.

---

### Task 1: Таблицы сообщений, подключений, состояния и времени ответа

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/006-create-messages-and-telegram.yaml`
- Create: `backend/src/main/java/com/vibe/crm/messages/{Direction,Message,MessageRepository,ResponseEvent,ResponseEventRepository,ResponseTracker}.java`
- Create: `backend/src/main/java/com/vibe/crm/telegram/{BusinessConnection,BusinessConnectionRepository,TelegramState,TelegramStateRepository}.java`
- Modify: `backend/src/test/java/com/vibe/crm/TestData.java` (`clearLeads` чистит новые таблицы)
- Test: `backend/src/test/java/com/vibe/crm/messages/ResponseTrackerTest.java`

**Interfaces:**
- Produces:
  - `enum Direction { IN, OUT }`
  - `Message`: `Message(Lead lead, long telegramMessageId, Direction direction, String text, Instant sentAt, User author)`; геттеры `id, lead, telegramMessageId, direction, text, sentAt, author, editedAt, deleted`; `void edit(String text, Instant editedAt)`, `void markDeleted()`.
  - `MessageRepository`: `boolean existsByLeadAndTelegramMessageId(Lead, long)`, `Optional<Message> findByLeadAndTelegramMessageId(Lead, long)`, `List<Message> findByLeadAndTelegramMessageIdIn(Lead, Collection<Long>)`, `List<Message> findByLeadOrderBySentAtAscIdAsc(Lead)`, `Optional<Message> findFirstByLeadOrderBySentAtDescIdDesc(Lead)`.
  - `ResponseEvent`: `ResponseEvent(Lead lead, User author, long waitedSeconds, Instant answeredAt)`; геттеры.
  - `ResponseEventRepository extends JpaRepository<ResponseEvent, Long>`: `List<ResponseEvent> findByLeadOrderByAnsweredAtAsc(Lead)`.
  - `ResponseTracker` (`@Service`): `void onIncoming(Lead lead, Instant at)` — ставит `awaitingReplySince`, если пусто; `void onOutgoing(Lead lead, Instant at, Long authorId)` — если ждём ответа: пишет `ResponseEvent(waited = max(0, at − awaitingReplySince))`, очищает ожидание.
  - `BusinessConnection`: `BusinessConnection(String id)`; поля `id, ownerTelegramUserId, userChatId, canReply, enabled, updatedAt` с геттерами/сеттерами.
  - `BusinessConnectionRepository extends JpaRepository<BusinessConnection, String>`: `Optional<BusinessConnection> findFirstByEnabledTrueOrderByUpdatedAtDesc()`.
  - `TelegramState`: ключ-значение; `TelegramStateRepository extends JpaRepository<TelegramState, String>`.

- [ ] **Step 1: Падающий тест `ResponseTrackerTest`**

```java
package com.vibe.crm.messages;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.IntegrationTest;
import com.vibe.crm.TestData;
import com.vibe.crm.leads.Lead;
import com.vibe.crm.users.Role;
import com.vibe.crm.users.User;

@IntegrationTest
@Transactional
class ResponseTrackerTest {

	@Autowired
	TestData data;

	@Autowired
	ResponseTracker tracker;

	@Autowired
	ResponseEventRepository responses;

	@BeforeEach
	void clean() {
		data.resetStatusesAndTags();
	}

	@Test
	void seriesOfIncomingThenReplyIsOneResponse() {
		Lead lead = data.lead(1);
		User maria = data.user("rt-maria", "maria-pass", Role.MANAGER);
		Instant t0 = Instant.parse("2026-10-13T12:47:00Z");

		tracker.onIncoming(lead, t0);
		tracker.onIncoming(lead, t0.plusSeconds(60));
		tracker.onOutgoing(lead, t0.plusSeconds(15 * 60), maria.getId());
		tracker.onOutgoing(lead, t0.plusSeconds(16 * 60), maria.getId());

		assertThat(responses.findByLeadOrderByAnsweredAtAsc(lead)).singleElement().satisfies(r -> {
			assertThat(r.getWaitedSeconds()).isEqualTo(15 * 60);
			assertThat(r.getAuthor().getId()).isEqualTo(maria.getId());
		});
		assertThat(lead.getAwaitingReplySince()).isNull();
	}

	@Test
	void eachReplyAfterNewIncomingIsCounted() {
		Lead lead = data.lead(2);
		Instant t0 = Instant.parse("2026-10-13T12:00:00Z");

		tracker.onIncoming(lead, t0);
		tracker.onOutgoing(lead, t0.plusSeconds(30), null);
		tracker.onIncoming(lead, t0.plusSeconds(100));
		tracker.onOutgoing(lead, t0.plusSeconds(400), null);

		assertThat(responses.findByLeadOrderByAnsweredAtAsc(lead)).extracting(ResponseEvent::getWaitedSeconds)
			.containsExactly(30L, 300L);
	}

	@Test
	void outgoingWithoutWaitingIsNotAResponse() {
		Lead lead = data.lead(3);
		tracker.onOutgoing(lead, Instant.now(), null);
		assertThat(responses.findByLeadOrderByAnsweredAtAsc(lead)).isEmpty();
	}

}
```

- [ ] **Step 2: Запустить — падает компиляция.**

- [ ] **Step 3: Миграция `006-create-messages-and-telegram.yaml`**

```yaml
databaseChangeLog:
  - changeSet:
      id: 006-create-messages-and-telegram
      author: crm
      changes:
        - createTable:
            tableName: messages
            columns:
              - column: { name: id, type: bigint, autoIncrement: true, constraints: { primaryKey: true, primaryKeyName: pk_messages } }
              - column: { name: lead_id, type: bigint, constraints: { nullable: false, foreignKeyName: fk_messages_lead, references: leads(id), deleteCascade: true } }
              - column: { name: telegram_message_id, type: bigint, constraints: { nullable: false } }
              - column: { name: direction, type: varchar(8), constraints: { nullable: false } }
              - column: { name: text, type: text, constraints: { nullable: false } }
              - column: { name: sent_at, type: timestamp with time zone, constraints: { nullable: false } }
              - column: { name: author_id, type: bigint, constraints: { foreignKeyName: fk_messages_author, references: users(id) } }
              - column: { name: edited_at, type: timestamp with time zone }
              - column: { name: deleted, type: boolean, defaultValueBoolean: false, constraints: { nullable: false } }
        - addUniqueConstraint: { tableName: messages, columnNames: 'lead_id, telegram_message_id', constraintName: uq_messages_lead_telegram_message }
        - createIndex: { tableName: messages, indexName: idx_messages_lead_sent_at, columns: [ { column: { name: lead_id } }, { column: { name: sent_at } } ] }
        - createTable:
            tableName: response_events
            columns:
              - column: { name: id, type: bigint, autoIncrement: true, constraints: { primaryKey: true, primaryKeyName: pk_response_events } }
              - column: { name: lead_id, type: bigint, constraints: { nullable: false, foreignKeyName: fk_response_events_lead, references: leads(id), deleteCascade: true } }
              - column: { name: author_id, type: bigint, constraints: { foreignKeyName: fk_response_events_author, references: users(id) } }
              - column: { name: waited_seconds, type: bigint, constraints: { nullable: false } }
              - column: { name: answered_at, type: timestamp with time zone, constraints: { nullable: false } }
        - createIndex: { tableName: response_events, indexName: idx_response_events_answered_at, columns: [ { column: { name: answered_at } } ] }
        - createTable:
            tableName: business_connections
            columns:
              - column: { name: id, type: varchar(128), constraints: { primaryKey: true, primaryKeyName: pk_business_connections } }
              - column: { name: owner_telegram_user_id, type: bigint, constraints: { nullable: false } }
              - column: { name: user_chat_id, type: bigint, constraints: { nullable: false } }
              - column: { name: can_reply, type: boolean, constraints: { nullable: false } }
              - column: { name: enabled, type: boolean, constraints: { nullable: false } }
              - column: { name: updated_at, type: timestamp with time zone, constraints: { nullable: false } }
        - createTable:
            tableName: telegram_state
            columns:
              - column: { name: key, type: varchar(64), constraints: { primaryKey: true, primaryKeyName: pk_telegram_state } }
              - column: { name: value, type: varchar(255), constraints: { nullable: false } }
```

- [ ] **Step 4: Пакет `messages`**

`messages/Direction.java`:
```java
package com.vibe.crm.messages;

public enum Direction {
	IN, OUT
}
```

`messages/Message.java`:
```java
package com.vibe.crm.messages;

import java.time.Instant;

import com.vibe.crm.leads.Lead;
import com.vibe.crm.users.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Сообщение переписки с лидом. author заполнен, только если ответ отправлен из CRM. */
@Entity
@Table(name = "messages")
public class Message {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "lead_id", nullable = false)
	private Lead lead;

	@Column(name = "telegram_message_id", nullable = false)
	private long telegramMessageId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 8)
	private Direction direction;

	@Column(nullable = false, columnDefinition = "text")
	private String text;

	@Column(name = "sent_at", nullable = false)
	private Instant sentAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "author_id")
	private User author;

	@Column(name = "edited_at")
	private Instant editedAt;

	@Column(nullable = false)
	private boolean deleted;

	protected Message() {
	}

	public Message(Lead lead, long telegramMessageId, Direction direction, String text, Instant sentAt, User author) {
		this.lead = lead;
		this.telegramMessageId = telegramMessageId;
		this.direction = direction;
		this.text = text;
		this.sentAt = sentAt;
		this.author = author;
	}

	public void edit(String text, Instant editedAt) {
		this.text = text;
		this.editedAt = editedAt;
	}

	public void markDeleted() {
		this.deleted = true;
	}

	public Long getId() {
		return id;
	}

	public Lead getLead() {
		return lead;
	}

	public long getTelegramMessageId() {
		return telegramMessageId;
	}

	public Direction getDirection() {
		return direction;
	}

	public String getText() {
		return text;
	}

	public Instant getSentAt() {
		return sentAt;
	}

	public User getAuthor() {
		return author;
	}

	public Instant getEditedAt() {
		return editedAt;
	}

	public boolean isDeleted() {
		return deleted;
	}

}
```

`messages/MessageRepository.java`:
```java
package com.vibe.crm.messages;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vibe.crm.leads.Lead;

public interface MessageRepository extends JpaRepository<Message, Long> {

	boolean existsByLeadAndTelegramMessageId(Lead lead, long telegramMessageId);

	Optional<Message> findByLeadAndTelegramMessageId(Lead lead, long telegramMessageId);

	List<Message> findByLeadAndTelegramMessageIdIn(Lead lead, Collection<Long> telegramMessageIds);

	List<Message> findByLeadOrderBySentAtAscIdAsc(Lead lead);

	Optional<Message> findFirstByLeadOrderBySentAtDescIdDesc(Lead lead);

}
```

`messages/ResponseEvent.java`:
```java
package com.vibe.crm.messages;

import java.time.Instant;

import com.vibe.crm.leads.Lead;
import com.vibe.crm.users.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Один ответ лиду: сколько секунд лид ждал. author == null — ответ из приложения Telegram. */
@Entity
@Table(name = "response_events")
public class ResponseEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "lead_id", nullable = false)
	private Lead lead;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "author_id")
	private User author;

	@Column(name = "waited_seconds", nullable = false)
	private long waitedSeconds;

	@Column(name = "answered_at", nullable = false)
	private Instant answeredAt;

	protected ResponseEvent() {
	}

	public ResponseEvent(Lead lead, User author, long waitedSeconds, Instant answeredAt) {
		this.lead = lead;
		this.author = author;
		this.waitedSeconds = waitedSeconds;
		this.answeredAt = answeredAt;
	}

	public Long getId() {
		return id;
	}

	public Lead getLead() {
		return lead;
	}

	public User getAuthor() {
		return author;
	}

	public long getWaitedSeconds() {
		return waitedSeconds;
	}

	public Instant getAnsweredAt() {
		return answeredAt;
	}

}
```

`messages/ResponseEventRepository.java`:
```java
package com.vibe.crm.messages;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vibe.crm.leads.Lead;

public interface ResponseEventRepository extends JpaRepository<ResponseEvent, Long> {

	List<ResponseEvent> findByLeadOrderByAnsweredAtAsc(Lead lead);

}
```

`messages/ResponseTracker.java`:
```java
package com.vibe.crm.messages;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.leads.Lead;
import com.vibe.crm.users.UserRepository;

/**
 * Время ответа: серия входящих сообщений — одно ожидание, первый исходящий после неё — один ответ.
 */
@Service
@Transactional
public class ResponseTracker {

	private final ResponseEventRepository responses;

	private final UserRepository users;

	public ResponseTracker(ResponseEventRepository responses, UserRepository users) {
		this.responses = responses;
		this.users = users;
	}

	public void onIncoming(Lead lead, Instant at) {
		if (lead.getAwaitingReplySince() == null) {
			lead.setAwaitingReplySince(at);
		}
	}

	public void onOutgoing(Lead lead, Instant at, Long authorId) {
		Instant since = lead.getAwaitingReplySince();
		if (since == null) {
			return;
		}
		long waited = Math.max(0, Duration.between(since, at).toSeconds());
		responses.save(new ResponseEvent(lead, authorId == null ? null : users.getReferenceById(authorId), waited, at));
		lead.setAwaitingReplySince(null);
	}

}
```

- [ ] **Step 5: Сущности `telegram`**

`telegram/BusinessConnection.java`:
```java
package com.vibe.crm.telegram;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Подключение бота к бизнес-аккаунту. owner — сам аккаунт компании. */
@Entity
@Table(name = "business_connections")
public class BusinessConnection {

	@Id
	@Column(length = 128)
	private String id;

	@Column(name = "owner_telegram_user_id", nullable = false)
	private long ownerTelegramUserId;

	@Column(name = "user_chat_id", nullable = false)
	private long userChatId;

	@Column(name = "can_reply", nullable = false)
	private boolean canReply;

	@Column(nullable = false)
	private boolean enabled;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected BusinessConnection() {
	}

	public BusinessConnection(String id) {
		this.id = id;
	}

	public String getId() {
		return id;
	}

	public long getOwnerTelegramUserId() {
		return ownerTelegramUserId;
	}

	public void setOwnerTelegramUserId(long ownerTelegramUserId) {
		this.ownerTelegramUserId = ownerTelegramUserId;
	}

	public long getUserChatId() {
		return userChatId;
	}

	public void setUserChatId(long userChatId) {
		this.userChatId = userChatId;
	}

	public boolean isCanReply() {
		return canReply;
	}

	public void setCanReply(boolean canReply) {
		this.canReply = canReply;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}

}
```

`telegram/BusinessConnectionRepository.java`:
```java
package com.vibe.crm.telegram;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessConnectionRepository extends JpaRepository<BusinessConnection, String> {

	Optional<BusinessConnection> findFirstByEnabledTrueOrderByUpdatedAtDesc();

}
```

`telegram/TelegramState.java`:
```java
package com.vibe.crm.telegram;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Служебные значения приёма апдейтов (например, offset getUpdates). */
@Entity
@Table(name = "telegram_state")
public class TelegramState {

	public static final String OFFSET = "updates_offset";

	@Id
	@Column(length = 64)
	private String key;

	@Column(nullable = false)
	private String value;

	protected TelegramState() {
	}

	public TelegramState(String key, String value) {
		this.key = key;
		this.value = value;
	}

	public String getKey() {
		return key;
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}

}
```

`telegram/TelegramStateRepository.java`:
```java
package com.vibe.crm.telegram;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TelegramStateRepository extends JpaRepository<TelegramState, String> {

}
```

- [ ] **Step 6: `TestData.clearLeads()` — чистить новые таблицы перед лидами**

В начало метода `clearLeads()`:
```java
		em.createNativeQuery("delete from response_events").executeUpdate();
		em.createNativeQuery("delete from messages").executeUpdate();
```

и добавить метод:
```java
	@Transactional
	public void clearTelegram() {
		clearLeads();
		em.createNativeQuery("delete from business_connections").executeUpdate();
		em.createNativeQuery("delete from telegram_state").executeUpdate();
	}
```

- [ ] **Step 7: Тест → `Tests run: 3, Failures: 0`; `./mvnw -q verify`; commit** «Добавить хранение переписки и времени ответа».

---

### Task 2: DTO Bot API и HTTP-клиент

**Files:**
- Create: `backend/src/main/java/com/vibe/crm/telegram/api/{TgUser,TgChat,TgContact,TgMessage,TgBusinessBotRights,TgBusinessConnection,TgBusinessMessagesDeleted,TgUpdate,TgResponse}.java`
- Create: `backend/src/main/java/com/vibe/crm/telegram/{TelegramBotClient,HttpTelegramBotClient,TelegramApiException,TelegramProperties,TelegramConfig}.java`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/resources/application.properties`
- Test: `backend/src/test/java/com/vibe/crm/telegram/HttpTelegramBotClientTest.java`

**Interfaces:**
- Produces:
  - DTO (records, `@JsonIgnoreProperties(ignoreUnknown = true)`, snake_case через `@JsonProperty`):
    - `TgUser(long id, boolean isBot, String firstName, String lastName, String username)`
    - `TgChat(long id, String type, String firstName, String lastName, String username)`
    - `TgContact(String phoneNumber, String firstName, Long userId)`
    - `TgMessage(long messageId, TgUser from, TgChat chat, long date, Long editDate, String businessConnectionId, TgUser senderBusinessBot, String text, String caption, JsonNode photo, JsonNode video, JsonNode voice, JsonNode videoNote, JsonNode audio, JsonNode document, JsonNode sticker, JsonNode animation, TgContact contact, JsonNode location)`; `Instant sentAt()`.
    - `TgBusinessBotRights(boolean canReply)`
    - `TgBusinessConnection(String id, TgUser user, long userChatId, long date, TgBusinessBotRights rights, boolean isEnabled)`; `boolean canReply()` (rights != null && rights.canReply()).
    - `TgBusinessMessagesDeleted(String businessConnectionId, TgChat chat, List<Long> messageIds)`
    - `TgUpdate(long updateId, TgBusinessConnection businessConnection, TgMessage businessMessage, TgMessage editedBusinessMessage, TgBusinessMessagesDeleted deletedBusinessMessages)`
    - `TgResponse<T>(boolean ok, T result, Integer errorCode, String description)`
  - `TelegramBotClient`: `List<TgUpdate> getUpdates(long offset, int timeoutSeconds)`, `TgMessage sendMessage(String businessConnectionId, long chatId, String text)`, `TgBusinessConnection getBusinessConnection(String businessConnectionId)`.
  - `TelegramApiException extends RuntimeException`: `int errorCode()`, `String description()`.
  - `TelegramProperties` (`@ConfigurationProperties("crm.telegram")` record): `String botToken`, `boolean pollingEnabled`, `String apiUrl`; `boolean configured()` — токен не пуст.

- [ ] **Step 1: Выключить приём в тестах — `backend/src/test/resources/application.properties`**

```properties
# Тесты никогда не ходят в настоящий Telegram и не забирают реальные апдейты бота.
crm.telegram.bot-token=
crm.telegram.polling-enabled=false
```

- [ ] **Step 2: Свойства в `application.yml`** — дописать в блок `crm:`

```yaml
  telegram:
    bot-token: ${TELEGRAM_BOT_TOKEN:}
    polling-enabled: true
    api-url: https://api.telegram.org
```

- [ ] **Step 3: Падающий тест `HttpTelegramBotClientTest`**

```java
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
```

- [ ] **Step 4: Запустить — падает компиляция.**

- [ ] **Step 5: DTO в `telegram/api/`**

```java
package com.vibe.crm.telegram.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgUser(long id, @JsonProperty("is_bot") boolean isBot, @JsonProperty("first_name") String firstName,
		@JsonProperty("last_name") String lastName, String username) {
}
```

```java
package com.vibe.crm.telegram.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgChat(long id, String type, @JsonProperty("first_name") String firstName,
		@JsonProperty("last_name") String lastName, String username) {
}
```

```java
package com.vibe.crm.telegram.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgContact(@JsonProperty("phone_number") String phoneNumber, @JsonProperty("first_name") String firstName,
		@JsonProperty("user_id") Long userId) {
}
```

```java
package com.vibe.crm.telegram.api;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.JsonNode;

/** Сообщение Bot API. Медиа нужны только как признак наличия — поэтому JsonNode. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TgMessage(@JsonProperty("message_id") long messageId, TgUser from, TgChat chat, long date,
		@JsonProperty("edit_date") Long editDate, @JsonProperty("business_connection_id") String businessConnectionId,
		@JsonProperty("sender_business_bot") TgUser senderBusinessBot, String text, String caption, JsonNode photo,
		JsonNode video, JsonNode voice, @JsonProperty("video_note") JsonNode videoNote, JsonNode audio,
		JsonNode document, JsonNode sticker, JsonNode animation, TgContact contact, JsonNode location) {

	public Instant sentAt() {
		return Instant.ofEpochSecond(date);
	}

}
```

```java
package com.vibe.crm.telegram.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgBusinessBotRights(@JsonProperty("can_reply") boolean canReply) {
}
```

```java
package com.vibe.crm.telegram.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgBusinessConnection(String id, TgUser user, @JsonProperty("user_chat_id") long userChatId, long date,
		TgBusinessBotRights rights, @JsonProperty("is_enabled") boolean isEnabled) {

	public boolean canReply() {
		return rights != null && rights.canReply();
	}

}
```

```java
package com.vibe.crm.telegram.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgBusinessMessagesDeleted(@JsonProperty("business_connection_id") String businessConnectionId,
		TgChat chat, @JsonProperty("message_ids") List<Long> messageIds) {
}
```

```java
package com.vibe.crm.telegram.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgUpdate(@JsonProperty("update_id") long updateId,
		@JsonProperty("business_connection") TgBusinessConnection businessConnection,
		@JsonProperty("business_message") TgMessage businessMessage,
		@JsonProperty("edited_business_message") TgMessage editedBusinessMessage,
		@JsonProperty("deleted_business_messages") TgBusinessMessagesDeleted deletedBusinessMessages) {
}
```

```java
package com.vibe.crm.telegram.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TgResponse<T>(boolean ok, T result, @JsonProperty("error_code") Integer errorCode, String description) {
}
```

- [ ] **Step 6: Клиент**

`telegram/TelegramBotClient.java`:
```java
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
```

`telegram/TelegramApiException.java`:
```java
package com.vibe.crm.telegram;

public class TelegramApiException extends RuntimeException {

	private final int errorCode;

	private final String description;

	public TelegramApiException(int errorCode, String description) {
		super("Telegram API " + errorCode + ": " + description);
		this.errorCode = errorCode;
		this.description = description;
	}

	public int errorCode() {
		return errorCode;
	}

	public String description() {
		return description;
	}

}
```

`telegram/HttpTelegramBotClient.java`:
```java
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
```

`telegram/TelegramProperties.java`:
```java
package com.vibe.crm.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("crm.telegram")
public record TelegramProperties(String botToken, boolean pollingEnabled, String apiUrl) {

	public boolean configured() {
		return botToken != null && !botToken.isBlank();
	}

}
```

`telegram/TelegramConfig.java`:
```java
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
		// RestClient.Builder-бина в Boot 4 без spring-boot-restclient нет — собираем свой
		return new HttpTelegramBotClient(RestClient.builder().requestFactory(factory), props.apiUrl(),
				props.botToken() == null ? "" : props.botToken());
	}

}
```

- [ ] **Step 7: Тест → `Tests run: 3, Failures: 0`.** Если `defaultStatusHandler` с пустым обработчиком не даёт прочитать тело ошибки (тест `apiErrorBecomesException` падает с `HttpClientErrorException`), заменить `retrieve().body(type)` на `exchange((req, res) -> res.bodyTo(type))` и убрать `defaultStatusHandler`.

- [ ] **Step 8: `./mvnw -q verify`; commit** «Добавить клиент Telegram Bot API».

---

### Task 3: Обработка апдейтов

**Files:**
- Create: `backend/src/main/java/com/vibe/crm/telegram/MessageTexts.java`
- Create: `backend/src/main/java/com/vibe/crm/telegram/UpdateProcessor.java`
- Create: `backend/src/main/java/com/vibe/crm/leads/LeadActivity.java`
- Test: `backend/src/test/java/com/vibe/crm/telegram/MessageTextsTest.java`
- Test: `backend/src/test/java/com/vibe/crm/telegram/UpdateProcessorTest.java`
- Test: `backend/src/test/java/com/vibe/crm/telegram/FakeTelegramBotClient.java`

**Interfaces:**
- Consumes: Tasks 1–2, `StatusService.first()`, `LeadHistory`, `LeadRepository`, `ResponseTracker`.
- Produces:
  - `record LeadActivity(Long leadId, Kind kind)`, `enum Kind { LEAD_CREATED, LEAD_UPDATED, MESSAGE_CREATED }` — публикуется через `ApplicationEventPublisher` внутри транзакции.
  - `MessageTexts.describe(TgMessage) → String` — текст/подпись/пометка медиа по Global Constraints.
  - `UpdateProcessor.process(TgUpdate)` — `@Transactional`.
  - `FakeTelegramBotClient` (тесты): `Map<String, TgBusinessConnection> connections`, `List<TgUpdate> queue`, `List<String> sent`; `getUpdates` отдаёт и очищает `queue`, если `failNext` — бросает `TelegramApiException`.

- [ ] **Step 1: Падающий тест `MessageTextsTest`**

```java
package com.vibe.crm.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.vibe.crm.telegram.api.TgMessage;

import tools.jackson.databind.json.JsonMapper;

class MessageTextsTest {

	JsonMapper mapper = JsonMapper.builder().build();

	TgMessage msg(String json) {
		return mapper.readValue("{\"message_id\":1,\"date\":0," + json + "}", TgMessage.class);
	}

	@Test
	void plainText() {
		assertThat(MessageTexts.describe(msg("\"text\":\"Привет\""))).isEqualTo("Привет");
	}

	@Test
	void mediaWithoutCaptionIsPlaceholder() {
		assertThat(MessageTexts.describe(msg("\"photo\":[{}]"))).isEqualTo("[Фото]");
		assertThat(MessageTexts.describe(msg("\"voice\":{}"))).isEqualTo("[Голосовое сообщение]");
		assertThat(MessageTexts.describe(msg("\"video_note\":{}"))).isEqualTo("[Видеосообщение]");
		assertThat(MessageTexts.describe(msg("\"sticker\":{}"))).isEqualTo("[Стикер]");
		assertThat(MessageTexts.describe(msg("\"contact\":{\"phone_number\":\"1\"}"))).isEqualTo("[Контакт]");
	}

	@Test
	void mediaWithCaption() {
		assertThat(MessageTexts.describe(msg("\"document\":{},\"caption\":\"Договор\""))).isEqualTo("[Файл] Договор");
	}

	@Test
	void unknownContentIsAttachment() {
		assertThat(MessageTexts.describe(msg("\"poll\":{}"))).isEqualTo("[Вложение]");
	}

}
```

- [ ] **Step 2: `telegram/MessageTexts.java`**

```java
package com.vibe.crm.telegram;

import com.vibe.crm.telegram.api.TgMessage;

/** Текст сообщения для CRM: медиа не храним, вместо них — пометка. */
final class MessageTexts {

	private MessageTexts() {
	}

	static String describe(TgMessage m) {
		if (m.text() != null && !m.text().isBlank()) {
			return m.text();
		}
		String label = label(m);
		String caption = m.caption() == null ? "" : m.caption().trim();
		if (label == null) {
			return caption.isEmpty() ? "[Вложение]" : caption;
		}
		return caption.isEmpty() ? label : label + " " + caption;
	}

	private static String label(TgMessage m) {
		if (m.photo() != null) return "[Фото]";
		if (m.video() != null) return "[Видео]";
		if (m.voice() != null) return "[Голосовое сообщение]";
		if (m.videoNote() != null) return "[Видеосообщение]";
		if (m.audio() != null) return "[Аудио]";
		// у GIF Telegram дублирует поле document — поэтому animation проверяется раньше
		if (m.animation() != null) return "[GIF]";
		if (m.document() != null) return "[Файл]";
		if (m.sticker() != null) return "[Стикер]";
		if (m.contact() != null) return "[Контакт]";
		if (m.location() != null) return "[Геопозиция]";
		return null;
	}

}
```

- [ ] **Step 3: `MessageTextsTest` → `Tests run: 4, Failures: 0`.**

- [ ] **Step 4: `leads/LeadActivity.java`**

```java
package com.vibe.crm.leads;

/** Что-то изменилось у лида — сигнал для обновления экранов (SSE). Публикуется внутри транзакции. */
public record LeadActivity(Long leadId, Kind kind) {

	public enum Kind {
		LEAD_CREATED, LEAD_UPDATED, MESSAGE_CREATED
	}

}
```

- [ ] **Step 5: Тестовый `FakeTelegramBotClient`**

```java
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
```

и подключить его во всех интеграционных тестах — в `TestcontainersConfiguration` добавить бин (`@Primary` перекрывает настоящий клиент):

```java
	@Bean
	@org.springframework.context.annotation.Primary
	com.vibe.crm.telegram.FakeTelegramBotClient fakeTelegramBotClient() {
		return new com.vibe.crm.telegram.FakeTelegramBotClient();
	}
```

- [ ] **Step 6: Падающий тест `UpdateProcessorTest`**

```java
package com.vibe.crm.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.vibe.crm.IntegrationTest;
import com.vibe.crm.TestData;
import com.vibe.crm.leads.Lead;
import com.vibe.crm.leads.LeadEventRepository;
import com.vibe.crm.leads.LeadEventType;
import com.vibe.crm.leads.LeadEvent;
import com.vibe.crm.leads.LeadRepository;
import com.vibe.crm.messages.Direction;
import com.vibe.crm.messages.Message;
import com.vibe.crm.messages.MessageRepository;
import com.vibe.crm.messages.ResponseEventRepository;
import com.vibe.crm.telegram.api.TgUpdate;

import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class UpdateProcessorTest {

	static final long OWNER = 777000111L;

	static final long LEAD = 548213097L;

	@Autowired
	UpdateProcessor processor;

	@Autowired
	FakeTelegramBotClient telegram;

	@Autowired
	TestData data;

	@Autowired
	LeadRepository leads;

	@Autowired
	MessageRepository messages;

	@Autowired
	LeadEventRepository events;

	@Autowired
	ResponseEventRepository responses;

	@Autowired
	BusinessConnectionRepository connections;

	@Autowired
	TransactionTemplate tx;

	JsonMapper mapper = JsonMapper.builder().build();

	@BeforeEach
	void setUp() {
		data.resetStatusesAndTags();
		data.clearTelegram();
		telegram.reset();
		process("""
				{"update_id":1,"business_connection":{"id":"bc1","user":{"id":%d,"is_bot":false,"first_name":"Компания"},
				 "user_chat_id":%d,"date":1760350000,"rights":{"can_reply":true},"is_enabled":true}}
				""".formatted(OWNER, OWNER));
	}

	void process(String json) {
		processor.process(mapper.readValue(json, TgUpdate.class));
	}

	String incoming(long updateId, long messageId, long date, String content) {
		return """
				{"update_id":%d,"business_message":{"message_id":%d,"business_connection_id":"bc1","date":%d,
				 "from":{"id":%d,"is_bot":false,"first_name":"Дмитрий","last_name":"Орлов","username":"orlov_d"},
				 "chat":{"id":%d,"type":"private","first_name":"Дмитрий"},%s}}
				""".formatted(updateId, messageId, date, LEAD, LEAD, content);
	}

	String outgoingFromApp(long updateId, long messageId, long date, String text) {
		return """
				{"update_id":%d,"business_message":{"message_id":%d,"business_connection_id":"bc1","date":%d,
				 "from":{"id":%d,"is_bot":false,"first_name":"Компания"},
				 "chat":{"id":%d,"type":"private","first_name":"Дмитрий"},"text":"%s"}}
				""".formatted(updateId, messageId, date, OWNER, LEAD, text);
	}

	<T> T inTx(java.util.function.Supplier<T> s) {
		return tx.execute(t -> s.get());
	}

	@Test
	void connectionIsStored() {
		BusinessConnection c = connections.findById("bc1").orElseThrow();
		assertThat(c.getOwnerTelegramUserId()).isEqualTo(OWNER);
		assertThat(c.isCanReply()).isTrue();
		assertThat(c.isEnabled()).isTrue();
	}

	@Test
	void firstIncomingMessageCreatesLead() {
		process(incoming(2, 15, 1760359620, "\"text\":\"Нужна партия на 120 штук\""));

		inTx(() -> {
			Lead lead = leads.findByTelegramUserId(LEAD).orElseThrow();
			assertThat(lead.displayName()).isEqualTo("Дмитрий Орлов");
			assertThat(lead.getUsername()).isEqualTo("orlov_d");
			assertThat(lead.getStatus().getName()).isEqualTo("Новый");
			assertThat(lead.getManager()).isNull();
			assertThat(lead.getSource()).isNull();
			assertThat(lead.getBusinessConnectionId()).isEqualTo("bc1");
			assertThat(lead.getLastMessageText()).isEqualTo("Нужна партия на 120 штук");
			assertThat(lead.getLastIncomingAt().getEpochSecond()).isEqualTo(1760359620L);
			assertThat(lead.getAwaitingReplySince().getEpochSecond()).isEqualTo(1760359620L);
			assertThat(events.findByLeadOrderByCreatedAtDescIdDesc(lead)).extracting(LeadEvent::getType)
				.containsExactly(LeadEventType.CREATED);
			assertThat(messages.findByLeadOrderBySentAtAscIdAsc(lead)).singleElement().satisfies(m -> {
				assertThat(m.getDirection()).isEqualTo(Direction.IN);
				assertThat(m.getTelegramMessageId()).isEqualTo(15);
			});
			return null;
		});
	}

	@Test
	void secondMessageDoesNotCreateNewLeadAndDuplicatesAreIgnored() {
		process(incoming(2, 15, 1760359620, "\"text\":\"Первое\""));
		process(incoming(3, 16, 1760359680, "\"text\":\"Второе\""));
		process(incoming(3, 16, 1760359680, "\"text\":\"Второе\""));

		assertThat(leads.count()).isEqualTo(1);
		inTx(() -> {
			Lead lead = leads.findByTelegramUserId(LEAD).orElseThrow();
			assertThat(messages.findByLeadOrderBySentAtAscIdAsc(lead)).extracting(Message::getText)
				.containsExactly("Первое", "Второе");
			assertThat(lead.getLastMessageText()).isEqualTo("Второе");
			return null;
		});
	}

	@Test
	void replyFromTelegramAppIsStoredAndCountedWithoutAuthor() {
		process(incoming(2, 15, 1760359620, "\"text\":\"Сроки?\""));
		process(outgoingFromApp(3, 16, 1760360520, "5–7 рабочих дней"));

		inTx(() -> {
			Lead lead = leads.findByTelegramUserId(LEAD).orElseThrow();
			List<Message> all = messages.findByLeadOrderBySentAtAscIdAsc(lead);
			assertThat(all).extracting(Message::getDirection).containsExactly(Direction.IN, Direction.OUT);
			assertThat(all.get(1).getAuthor()).isNull();
			assertThat(responses.findByLeadOrderByAnsweredAtAsc(lead)).singleElement().satisfies(r -> {
				assertThat(r.getWaitedSeconds()).isEqualTo(900);
				assertThat(r.getAuthor()).isNull();
			});
			assertThat(lead.getAwaitingReplySince()).isNull();
			return null;
		});
	}

	@Test
	void ownerWritingFirstDoesNotCreateLead() {
		process(outgoingFromApp(2, 15, 1760359620, "Здравствуйте, это компания"));
		assertThat(leads.count()).isZero();
	}

	@Test
	void messagesSentByOurBotAreSkipped() {
		process(incoming(2, 15, 1760359620, "\"text\":\"Сроки?\""));
		process("""
				{"update_id":3,"business_message":{"message_id":16,"business_connection_id":"bc1","date":1760360520,
				 "from":{"id":%d,"is_bot":false,"first_name":"Компания"},
				 "sender_business_bot":{"id":8614677825,"is_bot":true,"first_name":"CRM секретарь"},
				 "chat":{"id":%d,"type":"private"},"text":"Ответ из CRM"}}
				""".formatted(OWNER, LEAD));

		inTx(() -> {
			Lead lead = leads.findByTelegramUserId(LEAD).orElseThrow();
			assertThat(messages.findByLeadOrderBySentAtAscIdAsc(lead)).hasSize(1);
			assertThat(responses.findByLeadOrderByAnsweredAtAsc(lead)).isEmpty();
			return null;
		});
	}

	@Test
	void mediaIsStoredAsPlaceholderAndOwnContactSetsPhone() {
		process(incoming(2, 15, 1760359620, "\"photo\":[{\"file_id\":\"x\"}],\"caption\":\"Вот так\""));
		process(incoming(3, 16, 1760359680,
				"\"contact\":{\"phone_number\":\"79000000000\",\"first_name\":\"Дмитрий\",\"user_id\":%d}".formatted(LEAD)));

		inTx(() -> {
			Lead lead = leads.findByTelegramUserId(LEAD).orElseThrow();
			assertThat(messages.findByLeadOrderBySentAtAscIdAsc(lead)).extracting(Message::getText)
				.containsExactly("[Фото] Вот так", "[Контакт]");
			assertThat(lead.getPhone()).isEqualTo("+79000000000");
			return null;
		});
	}

	@Test
	void foreignContactDoesNotSetPhone() {
		process(incoming(2, 15, 1760359620,
				"\"contact\":{\"phone_number\":\"+71111111111\",\"first_name\":\"Друг\",\"user_id\":42}"));
		inTx(() -> {
			assertThat(leads.findByTelegramUserId(LEAD).orElseThrow().getPhone()).isNull();
			return null;
		});
	}

	@Test
	void editAndDelete() {
		process(incoming(2, 15, 1760359620, "\"text\":\"Нужно 100 штук\""));
		process("""
				{"update_id":3,"edited_business_message":{"message_id":15,"business_connection_id":"bc1","date":1760359620,
				 "edit_date":1760359700,"from":{"id":%d,"is_bot":false,"first_name":"Дмитрий"},
				 "chat":{"id":%d,"type":"private"},"text":"Нужно 120 штук"}}
				""".formatted(LEAD, LEAD));

		inTx(() -> {
			Lead lead = leads.findByTelegramUserId(LEAD).orElseThrow();
			Message m = messages.findByLeadAndTelegramMessageId(lead, 15).orElseThrow();
			assertThat(m.getText()).isEqualTo("Нужно 120 штук");
			assertThat(m.getEditedAt().getEpochSecond()).isEqualTo(1760359700L);
			assertThat(lead.getLastMessageText()).isEqualTo("Нужно 120 штук");
			return null;
		});

		process("""
				{"update_id":4,"deleted_business_messages":{"business_connection_id":"bc1",
				 "chat":{"id":%d,"type":"private"},"message_ids":[15]}}
				""".formatted(LEAD));

		inTx(() -> {
			Lead lead = leads.findByTelegramUserId(LEAD).orElseThrow();
			assertThat(messages.findByLeadAndTelegramMessageId(lead, 15).orElseThrow().isDeleted()).isTrue();
			return null;
		});
	}

	@Test
	void groupMessagesAreIgnored() {
		process("""
				{"update_id":2,"business_message":{"message_id":15,"business_connection_id":"bc1","date":1760359620,
				 "from":{"id":%d,"is_bot":false,"first_name":"Дмитрий"},
				 "chat":{"id":-100123,"type":"supergroup"},"text":"Привет"}}
				""".formatted(LEAD));
		assertThat(leads.count()).isZero();
	}

	@Test
	void unknownConnectionIsFetchedFromTelegram() {
		telegram.connections.put("bc2", mapper.readValue("""
				{"id":"bc2","user":{"id":%d,"is_bot":false,"first_name":"Компания"},"user_chat_id":%d,
				 "date":1760350000,"rights":{"can_reply":false},"is_enabled":true}
				""".formatted(OWNER, OWNER), com.vibe.crm.telegram.api.TgBusinessConnection.class));

		process("""
				{"update_id":2,"business_message":{"message_id":15,"business_connection_id":"bc2","date":1760359620,
				 "from":{"id":%d,"is_bot":false,"first_name":"Дмитрий"},
				 "chat":{"id":%d,"type":"private"},"text":"Привет"}}
				""".formatted(LEAD, LEAD));

		assertThat(connections.findById("bc2").orElseThrow().isCanReply()).isFalse();
		assertThat(leads.count()).isEqualTo(1);
	}

	@Test
	void disconnectIsStored() {
		process("""
				{"update_id":5,"business_connection":{"id":"bc1","user":{"id":%d,"is_bot":false,"first_name":"Компания"},
				 "user_chat_id":%d,"date":1760350000,"is_enabled":false}}
				""".formatted(OWNER, OWNER));
		BusinessConnection c = connections.findById("bc1").orElseThrow();
		assertThat(c.isEnabled()).isFalse();
		assertThat(c.isCanReply()).isFalse();
	}
}
```

- [ ] **Step 7: Запустить — падает.**

- [ ] **Step 8: `telegram/UpdateProcessor.java`**

```java
package com.vibe.crm.telegram;

import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.leads.Lead;
import com.vibe.crm.leads.LeadActivity;
import com.vibe.crm.leads.LeadEventType;
import com.vibe.crm.leads.LeadHistory;
import com.vibe.crm.leads.LeadRepository;
import com.vibe.crm.messages.Direction;
import com.vibe.crm.messages.Message;
import com.vibe.crm.messages.MessageRepository;
import com.vibe.crm.messages.ResponseTracker;
import com.vibe.crm.statuses.StatusService;
import com.vibe.crm.telegram.api.TgBusinessConnection;
import com.vibe.crm.telegram.api.TgBusinessMessagesDeleted;
import com.vibe.crm.telegram.api.TgMessage;
import com.vibe.crm.telegram.api.TgUpdate;
import com.vibe.crm.telegram.api.TgUser;

/** Превращает апдейты Telegram Business в лидов и переписку. Один апдейт — одна транзакция. */
@Service
public class UpdateProcessor {

	private static final Logger log = LoggerFactory.getLogger(UpdateProcessor.class);

	private final BusinessConnectionRepository connections;

	private final LeadRepository leads;

	private final MessageRepository messages;

	private final StatusService statuses;

	private final LeadHistory history;

	private final ResponseTracker responses;

	private final TelegramBotClient telegram;

	private final ApplicationEventPublisher events;

	public UpdateProcessor(BusinessConnectionRepository connections, LeadRepository leads, MessageRepository messages,
			StatusService statuses, LeadHistory history, ResponseTracker responses, TelegramBotClient telegram,
			ApplicationEventPublisher events) {
		this.connections = connections;
		this.leads = leads;
		this.messages = messages;
		this.statuses = statuses;
		this.history = history;
		this.responses = responses;
		this.telegram = telegram;
		this.events = events;
	}

	@Transactional
	public void process(TgUpdate update) {
		if (update.businessConnection() != null) {
			saveConnection(update.businessConnection());
		}
		if (update.businessMessage() != null) {
			onMessage(update.businessMessage());
		}
		if (update.editedBusinessMessage() != null) {
			onEdit(update.editedBusinessMessage());
		}
		if (update.deletedBusinessMessages() != null) {
			onDelete(update.deletedBusinessMessages());
		}
	}

	private BusinessConnection saveConnection(TgBusinessConnection c) {
		BusinessConnection entity = connections.findById(c.id()).orElseGet(() -> new BusinessConnection(c.id()));
		entity.setOwnerTelegramUserId(c.user().id());
		entity.setUserChatId(c.userChatId());
		entity.setCanReply(c.canReply());
		entity.setEnabled(c.isEnabled());
		entity.setUpdatedAt(Instant.now());
		return connections.save(entity);
	}

	private Optional<BusinessConnection> connection(String id) {
		if (id == null) {
			return Optional.empty();
		}
		return connections.findById(id).or(() -> {
			try {
				return Optional.of(saveConnection(telegram.getBusinessConnection(id)));
			}
			catch (TelegramApiException e) {
				log.warn("Не удалось получить подключение {}: {}", id, e.getMessage());
				return Optional.empty();
			}
		});
	}

	private void onMessage(TgMessage m) {
		if (m.chat() == null || !"private".equals(m.chat().type()) || m.from() == null) {
			return;
		}
		if (m.senderBusinessBot() != null) {
			return; // отправлено из CRM — сохраняется в момент отправки
		}
		Optional<BusinessConnection> connection = connection(m.businessConnectionId());
		if (connection.isEmpty()) {
			log.warn("Сообщение {} без известного подключения {} — пропущено", m.messageId(), m.businessConnectionId());
			return;
		}
		boolean outgoing = m.from().id() == connection.get().getOwnerTelegramUserId();
		if (outgoing) {
			onOutgoingFromApp(m);
		}
		else {
			onIncoming(m);
		}
	}

	private void onIncoming(TgMessage m) {
		Instant at = m.sentAt();
		TgUser from = m.from();
		Lead lead = leads.findByTelegramUserId(from.id()).orElse(null);
		boolean created = lead == null;
		if (created) {
			lead = leads.save(new Lead(from.id(), m.chat().id(), statuses.first(), at));
			history.record(lead, null, LeadEventType.CREATED, null, null);
		}
		else if (messages.existsByLeadAndTelegramMessageId(lead, m.messageId())) {
			return;
		}
		lead.setTelegramChatId(m.chat().id());
		lead.setBusinessConnectionId(m.businessConnectionId());
		lead.setFirstName(from.firstName());
		lead.setLastName(from.lastName());
		lead.setUsername(from.username());
		if (m.contact() != null && m.contact().userId() != null && m.contact().userId() == from.id()) {
			lead.setPhone(normalizePhone(m.contact().phoneNumber()));
		}
		String text = MessageTexts.describe(m);
		messages.save(new Message(lead, m.messageId(), Direction.IN, text, at, null));
		touch(lead, text, at);
		lead.setLastIncomingAt(at);
		responses.onIncoming(lead, at);
		events.publishEvent(new LeadActivity(lead.getId(), created ? LeadActivity.Kind.LEAD_CREATED : LeadActivity.Kind.MESSAGE_CREATED));
	}

	private void onOutgoingFromApp(TgMessage m) {
		Optional<Lead> found = leads.findByTelegramChatId(m.chat().id());
		if (found.isEmpty()) {
			return; // лидом становится только тот, кто написал сам
		}
		Lead lead = found.get();
		if (messages.existsByLeadAndTelegramMessageId(lead, m.messageId())) {
			return;
		}
		Instant at = m.sentAt();
		String text = MessageTexts.describe(m);
		messages.save(new Message(lead, m.messageId(), Direction.OUT, text, at, null));
		touch(lead, text, at);
		responses.onOutgoing(lead, at, null);
		events.publishEvent(new LeadActivity(lead.getId(), LeadActivity.Kind.MESSAGE_CREATED));
	}

	private void onEdit(TgMessage m) {
		if (m.chat() == null) {
			return;
		}
		leads.findByTelegramChatId(m.chat().id())
			.flatMap(lead -> messages.findByLeadAndTelegramMessageId(lead, m.messageId()))
			.ifPresent(message -> {
				String text = MessageTexts.describe(m);
				message.edit(text, m.editDate() == null ? Instant.now() : Instant.ofEpochSecond(m.editDate()));
				Lead lead = message.getLead();
				messages.findFirstByLeadOrderBySentAtDescIdDesc(lead)
					.filter(latest -> latest.getId().equals(message.getId()))
					.ifPresent(latest -> lead.setLastMessageText(text));
				events.publishEvent(new LeadActivity(lead.getId(), LeadActivity.Kind.MESSAGE_CREATED));
			});
	}

	private void onDelete(TgBusinessMessagesDeleted d) {
		if (d.chat() == null || d.messageIds() == null) {
			return;
		}
		leads.findByTelegramChatId(d.chat().id()).ifPresent(lead -> {
			messages.findByLeadAndTelegramMessageIdIn(lead, d.messageIds()).forEach(Message::markDeleted);
			events.publishEvent(new LeadActivity(lead.getId(), LeadActivity.Kind.MESSAGE_CREATED));
		});
	}

	private static void touch(Lead lead, String text, Instant at) {
		if (lead.getLastMessageAt() == null || !at.isBefore(lead.getLastMessageAt())) {
			lead.setLastMessageAt(at);
			lead.setLastMessageText(text);
		}
	}

	static String normalizePhone(String phone) {
		if (phone == null || phone.isBlank()) {
			return null;
		}
		String p = phone.trim();
		return p.startsWith("+") ? p : "+" + p;
	}

}
```

- [ ] **Step 9: Тесты `UpdateProcessorTest` → все зелёные; `./mvnw -q verify`; commit** «Принимать лидов и переписку из Telegram».

---

### Task 4: Фоновый приём апдейтов

**Files:**
- Create: `backend/src/main/java/com/vibe/crm/telegram/UpdatePoller.java`
- Test: `backend/src/test/java/com/vibe/crm/telegram/UpdatePollerTest.java`

**Interfaces:**
- Consumes: `TelegramBotClient`, `UpdateProcessor`, `TelegramStateRepository`, `TelegramProperties`.
- Produces: `UpdatePoller implements SmartLifecycle`; `int pollOnce()` — один запрос `getUpdates` и обработка пачки, возвращает число апдейтов; offset сохраняется после каждого апдейта (`update_id + 1`), ошибка обработки одного апдейта логируется и не останавливает пачку. Фоновый поток стартует, только если `pollingEnabled && configured()`; при ошибке запроса — пауза 1, 2, 4… до 60 с; ошибки 401/404 (неверный токен) останавливают приём с записью в лог.

- [ ] **Step 1: Падающий тест `UpdatePollerTest`**

```java
package com.vibe.crm.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.vibe.crm.IntegrationTest;
import com.vibe.crm.TestData;
import com.vibe.crm.leads.LeadRepository;
import com.vibe.crm.telegram.api.TgUpdate;

import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class UpdatePollerTest {

	@Autowired
	UpdatePoller poller;

	@Autowired
	FakeTelegramBotClient telegram;

	@Autowired
	TelegramStateRepository state;

	@Autowired
	LeadRepository leads;

	@Autowired
	TestData data;

	JsonMapper mapper = JsonMapper.builder().build();

	@BeforeEach
	void setUp() {
		data.resetStatusesAndTags();
		data.clearTelegram();
		telegram.reset();
	}

	TgUpdate update(String json) {
		return mapper.readValue(json, TgUpdate.class);
	}

	@Test
	void pollingIsNotRunningInTests() {
		assertThat(poller.isRunning()).isFalse();
	}

	@Test
	void processesBatchAndStoresOffset() {
		telegram.queue.add(update("""
				{"update_id":41,"business_connection":{"id":"bc1","user":{"id":1,"is_bot":false,"first_name":"К"},
				 "user_chat_id":1,"date":1,"rights":{"can_reply":true},"is_enabled":true}}
				"""));
		telegram.queue.add(update("""
				{"update_id":42,"business_message":{"message_id":5,"business_connection_id":"bc1","date":1760359620,
				 "from":{"id":2,"is_bot":false,"first_name":"Анна"},"chat":{"id":2,"type":"private"},"text":"Привет"}}
				"""));

		assertThat(poller.pollOnce()).isEqualTo(2);
		assertThat(state.findById(TelegramState.OFFSET).orElseThrow().getValue()).isEqualTo("43");
		assertThat(leads.count()).isEqualTo(1);

		poller.pollOnce();
		assertThat(telegram.offsets).containsExactly(0L, 43L);
	}

	@Test
	void brokenUpdateDoesNotStopTheBatch() {
		telegram.queue.add(update("""
				{"update_id":50,"business_message":{"message_id":5,"business_connection_id":"unknown","date":1,
				 "from":{"id":2,"is_bot":false,"first_name":"Анна"},"chat":{"id":2,"type":"private"},"text":"x"}}
				"""));
		telegram.queue.add(update("""
				{"update_id":51,"business_connection":{"id":"bc1","user":{"id":1,"is_bot":false,"first_name":"К"},
				 "user_chat_id":1,"date":1,"rights":{"can_reply":true},"is_enabled":true}}
				"""));

		assertThat(poller.pollOnce()).isEqualTo(2);
		assertThat(state.findById(TelegramState.OFFSET).orElseThrow().getValue()).isEqualTo("52");
	}

}
```

- [ ] **Step 2: `telegram/UpdatePoller.java`**

```java
package com.vibe.crm.telegram;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.vibe.crm.telegram.api.TgUpdate;

/** Фоновый long polling getUpdates. Offset хранится в БД, чтобы не терять и не повторять апдейты. */
@Component
public class UpdatePoller implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(UpdatePoller.class);

	static final int TIMEOUT_SECONDS = 25;

	private final TelegramBotClient telegram;

	private final UpdateProcessor processor;

	private final TelegramStateRepository state;

	private final TelegramProperties props;

	private final TransactionTemplate tx;

	private volatile Thread thread;

	private volatile boolean running;

	public UpdatePoller(TelegramBotClient telegram, UpdateProcessor processor, TelegramStateRepository state,
			TelegramProperties props, TransactionTemplate tx) {
		this.telegram = telegram;
		this.processor = processor;
		this.state = state;
		this.props = props;
		this.tx = tx;
	}

	/** Один запрос к Telegram и обработка полученной пачки. */
	public int pollOnce() {
		List<TgUpdate> updates = telegram.getUpdates(offset(), TIMEOUT_SECONDS);
		for (TgUpdate update : updates) {
			try {
				processor.process(update);
			}
			catch (RuntimeException e) {
				log.error("Не удалось обработать апдейт {}", update.updateId(), e);
			}
			saveOffset(update.updateId() + 1);
		}
		return updates.size();
	}

	private long offset() {
		return state.findById(TelegramState.OFFSET).map(s -> Long.parseLong(s.getValue())).orElse(0L);
	}

	private void saveOffset(long offset) {
		tx.executeWithoutResult(t -> {
			TelegramState s = state.findById(TelegramState.OFFSET)
				.orElseGet(() -> new TelegramState(TelegramState.OFFSET, "0"));
			s.setValue(Long.toString(offset));
			state.save(s);
		});
	}

	private void loop() {
		Duration backoff = Duration.ofSeconds(1);
		while (running) {
			try {
				pollOnce();
				backoff = Duration.ofSeconds(1);
			}
			catch (TelegramApiException e) {
				if (e.errorCode() == 401 || e.errorCode() == 404) {
					log.error("Telegram отклонил токен бота ({}). Приём сообщений остановлен — проверьте TELEGRAM_BOT_TOKEN.",
							e.description());
					running = false;
					return;
				}
				log.warn("Ошибка Telegram: {}. Повтор через {} с", e.getMessage(), backoff.toSeconds());
				backoff = sleep(backoff);
			}
			catch (RuntimeException e) {
				log.warn("Ошибка приёма апдейтов: {}. Повтор через {} с", e.toString(), backoff.toSeconds());
				backoff = sleep(backoff);
			}
		}
	}

	private Duration sleep(Duration backoff) {
		try {
			Thread.sleep(backoff);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			running = false;
		}
		Duration next = backoff.multipliedBy(2);
		return next.compareTo(Duration.ofSeconds(60)) > 0 ? Duration.ofSeconds(60) : next;
	}

	@Override
	public void start() {
		if (!props.pollingEnabled()) {
			log.info("Приём сообщений Telegram выключен (crm.telegram.polling-enabled=false)");
			return;
		}
		if (!props.configured()) {
			log.warn("TELEGRAM_BOT_TOKEN не задан — приём сообщений из Telegram не запущен");
			return;
		}
		running = true;
		thread = Thread.ofVirtual().name("telegram-poller").start(this::loop);
		log.info("Приём сообщений Telegram запущен");
	}

	@Override
	public void stop() {
		running = false;
		Thread t = thread;
		if (t != null) {
			t.interrupt();
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

}
```

- [ ] **Step 3: Тест → `Tests run: 3, Failures: 0`; `./mvnw -q verify`; commit** «Запустить фоновый приём сообщений из Telegram».

---

### Task 5: Живая проверка с настоящим ботом

- [ ] **Step 1:** Перезапустить бекенд (`.env` содержит `TELEGRAM_BOT_TOKEN`). В логе: «Приём сообщений Telegram запущен».
- [ ] **Step 2:** Попросить пользователя: подключить бота к аккаунту (Настройки → Telegram для бизнеса → Чат-боты → `@crm1423bot`, доступ ко всем личным чатам, право «Отвечать на сообщения»), затем написать аккаунту с другого Telegram и ответить из приложения.
- [ ] **Step 3:** Проверить в БД: `select * from business_connections; select id, first_name, username, status_id from leads; select lead_id, direction, text, author_id from messages; select * from response_events;` — подключение, лид, оба сообщения, одно время ответа. Это же подтверждает или опровергает предположение спеки о том, что ответы из приложения приходят боту.
- [ ] **Step 4:** Push в `dev` по скиллу `github-push`.
