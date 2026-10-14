# Этап 4. Бекенд: API лидов, ответ из CRM, живые обновления, дашборд — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Всё, что нужно экранам: список лидов с фильтрами, карточка, переписка, история, изменение статуса/менеджера/источника/тегов, ответ лиду из CRM, SSE-события для живого обновления и статистика дашборда.

**Architecture:** `com.vibe.crm.leads` — `LeadQueryService` (фильтры через JPA Specification), `LeadCommandService` (изменения с историей), `LeadController`. `com.vibe.crm.messages` — `ReplyService` (отправка через `TelegramBotClient`, правило 24 часов). `com.vibe.crm.events` — `SseHub` слушает `LeadActivity` после коммита. `com.vibe.crm.dashboard` — `DashboardService` на агрегирующих запросах.

**Tech Stack:** Spring Boot 4.1.1, Spring Data JPA Specifications, `SseEmitter`, Testcontainers.

## Global Constraints

- Всё из Global Constraints этапов 1–3.
- **Отклонение от спеки (осознанное):** вместо `PATCH /api/leads/{id}` — отдельные `PUT /api/leads/{id}/status {statusId}`, `PUT /api/leads/{id}/manager {managerId|null}`, `PUT /api/leads/{id}/source {source|null}`: так «снять значение» не путается с «не менять».
- Часовой пояс дат фильтров и дашборда: `crm.timezone` (по умолчанию `Europe/Moscow`).
- История: `STATUS_CHANGED` — названия статусов; `MANAGER_CHANGED` — имена (null = «Не назначен»); `SOURCE_CHANGED` — русские подписи источника (null = не указан); `TAG_ADDED` — `newValue` = имя тега; `TAG_REMOVED` — `oldValue` = имя тега.
- Подписи источников: CHANNEL «Канал», GROUP «Группа», ADS «Реклама», REFERRAL «Сарафан», OTHER «Другое».
- Каждое изменение лида публикует `LeadActivity(LEAD_UPDATED)`, новое сообщение — `MESSAGE_CREATED`.
- `spring.jpa.properties.hibernate.default_batch_fetch_size: 50` — список лидов без N+1.

---

### Task 1: Чтение лидов — список с фильтрами, карточка, переписка, история

**Files:**
- Modify: `backend/src/main/resources/application.yml` (`crm.timezone`, batch fetch size)
- Modify: `backend/src/main/java/com/vibe/crm/leads/Lead.java` (метод `registerMessage`)
- Modify: `backend/src/main/java/com/vibe/crm/telegram/UpdateProcessor.java` (использовать `registerMessage`)
- Create: `backend/src/main/java/com/vibe/crm/leads/{LeadSources,LeadFilter,LeadSpecifications,LeadViews,LeadQueryService,LeadController}.java`
- Create: `backend/src/main/java/com/vibe/crm/messages/ReplyAvailability.java`
- Test: `backend/src/test/java/com/vibe/crm/leads/LeadQueryTest.java`

**Interfaces:**
- Produces:
  - `Lead.registerMessage(String text, Instant at)` — обновляет `lastMessageAt/Text`, если сообщение не старше текущего последнего.
  - `LeadSources.label(LeadSource) → String` (null → null).
  - `record LeadFilter(String q, String messageText, LocalDate from, LocalDate to, Long managerId, boolean managerNone, LeadSource source, boolean sourceNone, List<Long> statusIds, List<Long> tagIds)`.
  - `LeadSpecifications.of(LeadFilter, ZoneId) → Specification<Lead>`.
  - `LeadViews` (records): `UserRef(Long id, String displayName)`, `TagRef(Long id, String name, TagColor color)`, `LeadListItem(Long id, String name, String username, Long statusId, List<TagRef> tags, UserRef manager, LeadSource source, String lastMessageText, Instant lastMessageAt)`, `LeadPage(List<LeadListItem> items, int page, int size, long found, long total, Map<Long, Long> statusCounts)`, `LeadCard(Long id, String name, String firstName, String lastName, String username, long telegramUserId, String phone, Long statusId, List<TagRef> tags, UserRef manager, LeadSource source, Instant createdAt, Instant lastMessageAt, boolean canReply, String replyBlockedReason, String telegramLink)`, `MessageView(Long id, Direction direction, String text, Instant sentAt, UserRef author, boolean edited, boolean deleted)`, `EventView(Long id, LeadEventType type, String oldValue, String newValue, UserRef actor, Instant createdAt)`.
  - `ReplyAvailability.reason(Lead, BusinessConnection /*nullable*/, Instant now) → String` (null = можно ответить).
  - `LeadQueryService`: `LeadPage list(LeadFilter, int page, int size)`, `LeadCard card(Long id)`, `List<MessageView> messages(Long id)`, `List<EventView> events(Long id)`, `long newCount()`, `Lead get(Long id)`.
  - HTTP: `GET /api/leads?q&messageText&from&to&manager(=id|none)&source(=KEY|none)&statusIds&tagIds&page&size`, `GET /api/leads/{id}`, `GET /api/leads/{id}/messages`, `GET /api/leads/{id}/events`, `GET /api/leads/new-count` → `{"count": n}`.

- [ ] **Step 1: Настройки** — в `application.yml`: в `spring.jpa` добавить

```yaml
    properties:
      hibernate:
        default_batch_fetch_size: 50
```

и в `crm:` добавить `timezone: Europe/Moscow`.

- [ ] **Step 2: `Lead.registerMessage`** — добавить в `Lead.java`:

```java
	/** Последнее сообщение для списка лидов: обновляется, если это сообщение не старше текущего. */
	public void registerMessage(String text, Instant at) {
		if (lastMessageAt == null || !at.isBefore(lastMessageAt)) {
			lastMessageAt = at;
			lastMessageText = text;
		}
	}
```

и в `UpdateProcessor` заменить вызовы `touch(lead, text, at)` на `lead.registerMessage(text, at)`, удалить метод `touch`.

- [ ] **Step 3: Падающий тест `LeadQueryTest`**

```java
package com.vibe.crm.leads;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.vibe.crm.IntegrationTest;
import com.vibe.crm.TestData;
import com.vibe.crm.TestLogin;
import com.vibe.crm.messages.Direction;
import com.vibe.crm.messages.Message;
import com.vibe.crm.messages.MessageRepository;
import com.vibe.crm.statuses.StatusRepository;
import com.vibe.crm.tags.TagRepository;
import com.vibe.crm.telegram.BusinessConnection;
import com.vibe.crm.telegram.BusinessConnectionRepository;
import com.vibe.crm.users.Role;
import com.vibe.crm.users.User;
import com.vibe.crm.users.UserRepository;

import jakarta.servlet.http.Cookie;

@IntegrationTest
class LeadQueryTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	LeadRepository leads;

	@Autowired
	StatusRepository statuses;

	@Autowired
	TagRepository tags;

	@Autowired
	UserRepository users;

	@Autowired
	MessageRepository messages;

	@Autowired
	BusinessConnectionRepository connections;

	@Autowired
	LeadHistory history;

	@Autowired
	TransactionTemplate tx;

	Cookie session;

	User maria;

	@BeforeEach
	void setUp() throws Exception {
		data.resetStatusesAndTags();
		data.clearTelegram();
		maria = data.user("lq-maria", "maria-pass", Role.MANAGER);
		session = TestLogin.as(mvc, "lq-maria", "maria-pass");
	}

	Long lead(long tgId, Consumer<Lead> setup) {
		Long id = data.lead(tgId).getId();
		tx.executeWithoutResult(t -> setup.accept(leads.findById(id).orElseThrow()));
		return id;
	}

	Long statusId(String name) {
		return statuses.findAllByOrderByPositionAsc().stream().filter(s -> s.getName().equals(name)).findFirst()
			.orElseThrow().getId();
	}

	Long tagId(String name) {
		return tags.findAllByOrderByIdAsc().stream().filter(t -> t.getName().equals(name)).findFirst().orElseThrow()
			.getId();
	}

	void seed() {
		lead(1, l -> {
			l.setFirstName("Анна");
			l.setLastName("Смирнова");
			l.setUsername("anna_sm");
			l.getTags().add(tags.findById(tagId("VIP")).orElseThrow());
			l.registerMessage("Сколько стоит доставка в Казань?", Instant.parse("2026-10-14T09:40:00Z"));
		});
		lead(2, l -> {
			l.setFirstName("Игорь");
			l.setUsername("ipetrov");
			l.setStatus(statuses.findById(statusId("В работе")).orElseThrow());
			l.setManager(users.findById(maria.getId()).orElseThrow());
			l.setSource(LeadSource.CHANNEL);
			l.getTags().add(tags.findById(tagId("Опт")).orElseThrow());
			l.registerMessage("Интересует оптовый прайс", Instant.parse("2026-10-14T08:05:00Z"));
		});
		lead(3, l -> {
			l.setFirstName("Дмитрий");
			l.registerMessage("Нужно до пятницы", Instant.parse("2026-10-12T12:47:00Z"));
		});
	}

	@Test
	void listIsSortedByLastMessageWithCounts() throws Exception {
		seed();
		mvc.perform(get("/api/leads").cookie(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.found").value(3))
			.andExpect(jsonPath("$.size").value(25))
			.andExpect(jsonPath("$.items[0].name").value("Анна Смирнова"))
			.andExpect(jsonPath("$.items[0].tags[0].name").value("VIP"))
			.andExpect(jsonPath("$.items[0].manager").doesNotExist())
			.andExpect(jsonPath("$.items[1].manager.displayName").value("lq-maria"))
			.andExpect(jsonPath("$.items[1].source").value("CHANNEL"))
			.andExpect(jsonPath("$.items[2].name").value("Дмитрий"))
			.andExpect(jsonPath("$.statusCounts['" + statusId("Новый") + "']").value(2));
	}

	@Test
	void filters() throws Exception {
		seed();
		mvc.perform(get("/api/leads").cookie(session).param("q", "SM"))
			.andExpect(jsonPath("$.found").value(1)).andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.items[0].username").value("anna_sm"));
		mvc.perform(get("/api/leads").cookie(session).param("messageText", "прайс"))
			.andExpect(jsonPath("$.items[0].username").value("ipetrov"));
		mvc.perform(get("/api/leads").cookie(session).param("manager", "none"))
			.andExpect(jsonPath("$.found").value(2));
		mvc.perform(get("/api/leads").cookie(session).param("manager", maria.getId().toString()))
			.andExpect(jsonPath("$.found").value(1));
		mvc.perform(get("/api/leads").cookie(session).param("source", "none"))
			.andExpect(jsonPath("$.found").value(2));
		mvc.perform(get("/api/leads").cookie(session).param("source", "CHANNEL"))
			.andExpect(jsonPath("$.found").value(1));
		mvc.perform(get("/api/leads").cookie(session).param("statusIds", statusId("В работе").toString()))
			.andExpect(jsonPath("$.found").value(1));
		mvc.perform(get("/api/leads").cookie(session).param("tagIds", tagId("VIP").toString(), tagId("Опт").toString()))
			.andExpect(jsonPath("$.found").value(2));
		// 14 октября по Москве — два лида; 12 октября — один
		mvc.perform(get("/api/leads").cookie(session).param("from", "2026-10-14").param("to", "2026-10-14"))
			.andExpect(jsonPath("$.found").value(2));
		mvc.perform(get("/api/leads").cookie(session).param("to", "2026-10-13"))
			.andExpect(jsonPath("$.found").value(1));
	}

	@Test
	void paging() throws Exception {
		for (int i = 0; i < 30; i++) {
			int n = i;
			lead(100 + i, l -> l.registerMessage("m" + n, Instant.parse("2026-10-01T00:00:00Z").plusSeconds(n)));
		}
		mvc.perform(get("/api/leads").cookie(session).param("page", "1"))
			.andExpect(jsonPath("$.items.length()").value(5))
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.found").value(30));
	}

	@Test
	void cardMessagesAndEvents() throws Exception {
		Long id = lead(548213097L, l -> {
			l.setFirstName("Дмитрий");
			l.setUsername("orlov_d");
			l.setPhone("+79000000000");
			l.setBusinessConnectionId("bc1");
			l.setLastIncomingAt(Instant.now().minusSeconds(3600));
		});
		tx.executeWithoutResult(t -> {
			BusinessConnection c = new BusinessConnection("bc1");
			c.setOwnerTelegramUserId(1);
			c.setUserChatId(1);
			c.setCanReply(true);
			c.setEnabled(true);
			c.setUpdatedAt(Instant.now());
			connections.save(c);
			Lead l = leads.findById(id).orElseThrow();
			messages.save(new Message(l, 15, Direction.IN, "Сроки?", Instant.parse("2026-10-13T12:47:00Z"), null));
			messages.save(new Message(l, 16, Direction.OUT, "5–7 дней", Instant.parse("2026-10-13T13:02:00Z"),
					users.findById(maria.getId()).orElseThrow()));
			history.record(l, null, LeadEventType.CREATED, null, null);
		});

		mvc.perform(get("/api/leads/{id}", id).cookie(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Дмитрий"))
			.andExpect(jsonPath("$.phone").value("+79000000000"))
			.andExpect(jsonPath("$.telegramUserId").value(548213097L))
			.andExpect(jsonPath("$.canReply").value(true))
			.andExpect(jsonPath("$.telegramLink").value("https://t.me/orlov_d"));
		mvc.perform(get("/api/leads/{id}/messages", id).cookie(session))
			.andExpect(jsonPath("$[0].direction").value("IN"))
			.andExpect(jsonPath("$[1].author.displayName").value("lq-maria"));
		mvc.perform(get("/api/leads/{id}/events", id).cookie(session))
			.andExpect(jsonPath("$[0].type").value("CREATED"))
			.andExpect(jsonPath("$[0].actor").doesNotExist());
	}

	@Test
	void replyIsBlockedAfter24HoursOrWithoutConnection() throws Exception {
		Long stale = lead(5, l -> {
			l.setBusinessConnectionId("bc1");
			l.setLastIncomingAt(Instant.now().minusSeconds(25 * 3600));
		});
		Long noConnection = lead(6, l -> l.setLastIncomingAt(Instant.now()));
		tx.executeWithoutResult(t -> {
			BusinessConnection c = new BusinessConnection("bc1");
			c.setCanReply(true);
			c.setEnabled(true);
			c.setUpdatedAt(Instant.now());
			connections.save(c);
		});

		mvc.perform(get("/api/leads/{id}", stale).cookie(session))
			.andExpect(jsonPath("$.canReply").value(false))
			.andExpect(jsonPath("$.replyBlockedReason")
				.value("Прошло больше 24 часов с последнего сообщения лида — ответьте ему в Telegram"))
			.andExpect(jsonPath("$.telegramLink").value("tg://user?id=5"));
		mvc.perform(get("/api/leads/{id}", noConnection).cookie(session))
			.andExpect(jsonPath("$.canReply").value(false))
			.andExpect(jsonPath("$.replyBlockedReason").value("Бот не подключён к аккаунту Telegram"));
	}

	@Test
	void newCountIsLeadsInFirstStatus() throws Exception {
		seed();
		mvc.perform(get("/api/leads/new-count").cookie(session)).andExpect(jsonPath("$.count").value(2));
	}

	@Test
	void unknownLeadIs404() throws Exception {
		mvc.perform(get("/api/leads/{id}", 999999).cookie(session))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.message").value("Лид не найден"));
	}

}
```

- [ ] **Step 4: Запустить — падает.**

- [ ] **Step 5: Код**

`leads/LeadSources.java`:
```java
package com.vibe.crm.leads;

public final class LeadSources {

	private LeadSources() {
	}

	public static String label(LeadSource source) {
		if (source == null) {
			return null;
		}
		return switch (source) {
			case CHANNEL -> "Канал";
			case GROUP -> "Группа";
			case ADS -> "Реклама";
			case REFERRAL -> "Сарафан";
			case OTHER -> "Другое";
		};
	}

}
```

`leads/LeadFilter.java`:
```java
package com.vibe.crm.leads;

import java.time.LocalDate;
import java.util.List;

public record LeadFilter(String q, String messageText, LocalDate from, LocalDate to, Long managerId,
		boolean managerNone, LeadSource source, boolean sourceNone, List<Long> statusIds, List<Long> tagIds) {
}
```

`leads/LeadSpecifications.java`:
```java
package com.vibe.crm.leads;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

final class LeadSpecifications {

	private LeadSpecifications() {
	}

	static Specification<Lead> of(LeadFilter f, ZoneId zone) {
		return (root, query, cb) -> {
			List<Predicate> ps = new ArrayList<>();
			if (notBlank(f.q())) {
				Expression<String> name = cb.concat(cb.concat(cb.coalesce(root.get("firstName"), ""), " "),
						cb.concat(cb.concat(cb.coalesce(root.get("lastName"), ""), " "),
								cb.coalesce(root.get("username"), "")));
				ps.add(cb.like(cb.lower(name), pattern(f.q()), '\\'));
			}
			if (notBlank(f.messageText())) {
				ps.add(cb.like(cb.lower(root.get("lastMessageText")), pattern(f.messageText()), '\\'));
			}
			if (f.from() != null) {
				ps.add(cb.greaterThanOrEqualTo(root.get("lastMessageAt"), f.from().atStartOfDay(zone).toInstant()));
			}
			if (f.to() != null) {
				ps.add(cb.lessThan(root.get("lastMessageAt"), f.to().plusDays(1).atStartOfDay(zone).toInstant()));
			}
			if (f.managerNone()) {
				ps.add(cb.isNull(root.get("manager")));
			}
			else if (f.managerId() != null) {
				ps.add(cb.equal(root.get("manager").get("id"), f.managerId()));
			}
			if (f.sourceNone()) {
				ps.add(cb.isNull(root.get("source")));
			}
			else if (f.source() != null) {
				ps.add(cb.equal(root.get("source"), f.source()));
			}
			if (f.statusIds() != null && !f.statusIds().isEmpty()) {
				ps.add(root.get("status").get("id").in(f.statusIds()));
			}
			if (f.tagIds() != null && !f.tagIds().isEmpty()) {
				ps.add(cb.exists(withAnyTag(query.subquery(Long.class), root, cb, f.tagIds())));
			}
			return cb.and(ps.toArray(Predicate[]::new));
		};
	}

	private static Subquery<Long> withAnyTag(Subquery<Long> sq, Root<Lead> root, CriteriaBuilder cb, List<Long> tagIds) {
		Root<Lead> l = sq.from(Lead.class);
		Join<Object, Object> tag = l.join("tags");
		return sq.select(l.get("id")).where(cb.equal(l.get("id"), root.get("id")), tag.get("id").in(tagIds));
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}

	private static String pattern(String s) {
		String escaped = s.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
		return "%" + escaped + "%";
	}

}
```

`leads/LeadViews.java`:
```java
package com.vibe.crm.leads;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vibe.crm.messages.Direction;
import com.vibe.crm.messages.Message;
import com.vibe.crm.tags.Tag;
import com.vibe.crm.tags.TagColor;
import com.vibe.crm.users.User;

/** Ответы API лидов. */
public final class LeadViews {

	private LeadViews() {
	}

	public record UserRef(Long id, String displayName) {

		static UserRef of(User u) {
			return u == null ? null : new UserRef(u.getId(), u.getDisplayName());
		}

	}

	public record TagRef(Long id, String name, TagColor color) {

		static List<TagRef> of(Lead lead) {
			return lead.getTags().stream().sorted(Comparator.comparing(Tag::getId))
				.map(t -> new TagRef(t.getId(), t.getName(), t.getColor())).toList();
		}

	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LeadListItem(Long id, String name, String username, Long statusId, List<TagRef> tags,
			UserRef manager, LeadSource source, String lastMessageText, Instant lastMessageAt) {

		static LeadListItem of(Lead l) {
			return new LeadListItem(l.getId(), l.displayName(), l.getUsername(), l.getStatus().getId(), TagRef.of(l),
					UserRef.of(l.getManager()), l.getSource(), l.getLastMessageText(), l.getLastMessageAt());
		}

	}

	public record LeadPage(List<LeadListItem> items, int page, int size, long found, long total,
			Map<Long, Long> statusCounts) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LeadCard(Long id, String name, String firstName, String lastName, String username,
			long telegramUserId, String phone, Long statusId, List<TagRef> tags, UserRef manager, LeadSource source,
			Instant createdAt, Instant lastMessageAt, boolean canReply, String replyBlockedReason, String telegramLink) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record MessageView(Long id, Direction direction, String text, Instant sentAt, UserRef author,
			boolean edited, boolean deleted) {

		public static MessageView of(Message m) {
			return new MessageView(m.getId(), m.getDirection(), m.getText(), m.getSentAt(), UserRef.of(m.getAuthor()),
					m.getEditedAt() != null, m.isDeleted());
		}

	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record EventView(Long id, LeadEventType type, String oldValue, String newValue, UserRef actor,
			Instant createdAt) {

		static EventView of(LeadEvent e) {
			return new EventView(e.getId(), e.getType(), e.getOldValue(), e.getNewValue(), UserRef.of(e.getActor()),
					e.getCreatedAt());
		}

	}

}
```

`messages/ReplyAvailability.java`:
```java
package com.vibe.crm.messages;

import java.time.Duration;
import java.time.Instant;

import com.vibe.crm.leads.Lead;
import com.vibe.crm.telegram.BusinessConnection;

/** Можно ли ответить лиду из CRM. Telegram разрешает это только в течение 24 часов после входящего. */
public final class ReplyAvailability {

	static final Duration WINDOW = Duration.ofHours(24);

	private ReplyAvailability() {
	}

	/** null — ответить можно; иначе причина, почему нельзя. */
	public static String reason(Lead lead, BusinessConnection connection, Instant now) {
		if (connection == null || !connection.isEnabled()) {
			return "Бот не подключён к аккаунту Telegram";
		}
		if (!connection.isCanReply()) {
			return "У бота нет права отвечать на сообщения — включите его в настройках Telegram для бизнеса";
		}
		if (lead.getLastIncomingAt() == null || lead.getLastIncomingAt().isBefore(now.minus(WINDOW))) {
			return "Прошло больше 24 часов с последнего сообщения лида — ответьте ему в Telegram";
		}
		return null;
	}

}
```

`leads/LeadQueryService.java`:
```java
package com.vibe.crm.leads;

import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.common.ApiException;
import com.vibe.crm.leads.LeadViews.EventView;
import com.vibe.crm.leads.LeadViews.LeadCard;
import com.vibe.crm.leads.LeadViews.LeadListItem;
import com.vibe.crm.leads.LeadViews.LeadPage;
import com.vibe.crm.leads.LeadViews.MessageView;
import com.vibe.crm.leads.LeadViews.TagRef;
import com.vibe.crm.leads.LeadViews.UserRef;
import com.vibe.crm.messages.MessageRepository;
import com.vibe.crm.messages.ReplyAvailability;
import com.vibe.crm.statuses.StatusService;
import com.vibe.crm.telegram.BusinessConnection;
import com.vibe.crm.telegram.BusinessConnectionRepository;

@Service
@Transactional(readOnly = true)
public class LeadQueryService {

	private static final Sort ORDER = Sort.by(Sort.Order.desc("lastMessageAt").nullsLast(), Sort.Order.desc("id"));

	private final LeadRepository leads;

	private final MessageRepository messages;

	private final LeadEventRepository events;

	private final BusinessConnectionRepository connections;

	private final StatusService statuses;

	private final ZoneId zone;

	public LeadQueryService(LeadRepository leads, MessageRepository messages, LeadEventRepository events,
			BusinessConnectionRepository connections, StatusService statuses,
			@Value("${crm.timezone:Europe/Moscow}") String timezone) {
		this.leads = leads;
		this.messages = messages;
		this.events = events;
		this.connections = connections;
		this.statuses = statuses;
		this.zone = ZoneId.of(timezone);
	}

	public Lead get(Long id) {
		return leads.findById(id).orElseThrow(() -> ApiException.notFound("Лид не найден"));
	}

	public LeadPage list(LeadFilter filter, int page, int size) {
		Page<Lead> result = leads.findAll(LeadSpecifications.of(filter, zone), PageRequest.of(page, size, ORDER));
		Map<Long, Long> counts = new HashMap<>();
		for (Object[] row : leads.countByStatus()) {
			counts.put((Long) row[0], (Long) row[1]);
		}
		return new LeadPage(result.getContent().stream().map(LeadListItem::of).toList(), page, size,
				result.getTotalElements(), leads.count(), counts);
	}

	public LeadCard card(Long id) {
		Lead l = get(id);
		BusinessConnection connection = l.getBusinessConnectionId() == null ? null
				: connections.findById(l.getBusinessConnectionId()).orElse(null);
		String reason = ReplyAvailability.reason(l, connection, Instant.now());
		String link = l.getUsername() != null && !l.getUsername().isBlank() ? "https://t.me/" + l.getUsername()
				: "tg://user?id=" + l.getTelegramUserId();
		return new LeadCard(l.getId(), l.displayName(), l.getFirstName(), l.getLastName(), l.getUsername(),
				l.getTelegramUserId(), l.getPhone(), l.getStatus().getId(), TagRef.of(l), UserRef.of(l.getManager()),
				l.getSource(), l.getCreatedAt(), l.getLastMessageAt(), reason == null, reason, link);
	}

	public List<MessageView> messages(Long id) {
		return messages.findByLeadOrderBySentAtAscIdAsc(get(id)).stream().map(MessageView::of).toList();
	}

	public List<EventView> events(Long id) {
		return events.findByLeadOrderByCreatedAtDescIdDesc(get(id)).stream().map(EventView::of).toList();
	}

	public long newCount() {
		return leads.countByStatus(statuses.first());
	}

}
```

`leads/LeadController.java` (чтение; изменения добавит Task 2):
```java
package com.vibe.crm.leads;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vibe.crm.common.ApiException;
import com.vibe.crm.leads.LeadViews.EventView;
import com.vibe.crm.leads.LeadViews.LeadCard;
import com.vibe.crm.leads.LeadViews.LeadPage;
import com.vibe.crm.leads.LeadViews.MessageView;

@RestController
@RequestMapping("/api/leads")
public class LeadController {

	private final LeadQueryService queries;

	public LeadController(LeadQueryService queries) {
		this.queries = queries;
	}

	@GetMapping
	public LeadPage list(@RequestParam(required = false) String q, @RequestParam(required = false) String messageText,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String manager, @RequestParam(required = false) String source,
			@RequestParam(required = false) List<Long> statusIds, @RequestParam(required = false) List<Long> tagIds,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
		boolean managerNone = "none".equals(manager);
		Long managerId = manager == null || managerNone || manager.isBlank() ? null : parseId(manager);
		boolean sourceNone = "none".equals(source);
		LeadSource src = source == null || sourceNone || source.isBlank() ? null : parseSource(source);
		LeadFilter filter = new LeadFilter(q, messageText, from, to, managerId, managerNone, src, sourceNone, statusIds,
				tagIds);
		return queries.list(filter, Math.max(page, 0), Math.clamp(size, 1, 100));
	}

	@GetMapping("/new-count")
	public Map<String, Long> newCount() {
		return Map.of("count", queries.newCount());
	}

	@GetMapping("/{id}")
	public LeadCard card(@PathVariable Long id) {
		return queries.card(id);
	}

	@GetMapping("/{id}/messages")
	public List<MessageView> messages(@PathVariable Long id) {
		return queries.messages(id);
	}

	@GetMapping("/{id}/events")
	public List<EventView> events(@PathVariable Long id) {
		return queries.events(id);
	}

	private static Long parseId(String value) {
		try {
			return Long.valueOf(value);
		}
		catch (NumberFormatException e) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Некорректный менеджер");
		}
	}

	private static LeadSource parseSource(String value) {
		try {
			return LeadSource.valueOf(value);
		}
		catch (IllegalArgumentException e) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Некорректный источник");
		}
	}

}
```

- [ ] **Step 6: Тест → `Tests run: 7, Failures: 0`; `./mvnw -q verify`; commit** «Добавить список лидов с фильтрами и карточку лида».

---

### Task 2: Изменение лида и ответ из CRM

**Files:**
- Create: `backend/src/main/java/com/vibe/crm/leads/LeadCommandService.java`
- Create: `backend/src/main/java/com/vibe/crm/messages/ReplyService.java`
- Modify: `backend/src/main/java/com/vibe/crm/leads/LeadController.java` (эндпоинты изменений)
- Test: `backend/src/test/java/com/vibe/crm/leads/LeadCommandTest.java`

**Interfaces:**
- Consumes: Task 1, `StatusService.get`, `TagService.get`, `UserService.get`, `LeadHistory`, `ResponseTracker`, `TelegramBotClient`, `CurrentUser`.
- Produces:
  - `LeadCommandService`: `void changeStatus(Long leadId, Long statusId)`, `void assign(Long leadId, Long managerId /*nullable*/)`, `void changeSource(Long leadId, LeadSource /*nullable*/)`, `void addTag(Long leadId, Long tagId)`, `void removeTag(Long leadId, Long tagId)` — без изменения значения не пишут историю; публикуют `LEAD_UPDATED`.
  - `ReplyService.reply(Long leadId, String text) → MessageView`; 409 с причиной из `ReplyAvailability`; ошибка Telegram → 502 «Telegram не принял сообщение: …».
  - HTTP: `PUT /api/leads/{id}/status {statusId}`, `PUT /api/leads/{id}/manager {managerId}`, `PUT /api/leads/{id}/source {source}`, `POST|DELETE /api/leads/{id}/tags/{tagId}` → 204; `POST /api/leads/{id}/messages {text}` → 201 `MessageView`. Назначать можно только активного пользователя (иначе 400 «Пользователь заблокирован»).

- [ ] **Step 1: Падающий тест `LeadCommandTest`**

```java
package com.vibe.crm.leads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.vibe.crm.IntegrationTest;
import com.vibe.crm.TestData;
import com.vibe.crm.TestLogin;
import com.vibe.crm.messages.ResponseEventRepository;
import com.vibe.crm.statuses.StatusRepository;
import com.vibe.crm.tags.TagRepository;
import com.vibe.crm.telegram.BusinessConnection;
import com.vibe.crm.telegram.BusinessConnectionRepository;
import com.vibe.crm.telegram.FakeTelegramBotClient;
import com.vibe.crm.users.Role;
import com.vibe.crm.users.User;
import com.vibe.crm.users.UserRepository;

import jakarta.servlet.http.Cookie;

@IntegrationTest
class LeadCommandTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	LeadRepository leads;

	@Autowired
	StatusRepository statuses;

	@Autowired
	TagRepository tags;

	@Autowired
	UserRepository users;

	@Autowired
	BusinessConnectionRepository connections;

	@Autowired
	ResponseEventRepository responses;

	@Autowired
	FakeTelegramBotClient telegram;

	@Autowired
	TransactionTemplate tx;

	Cookie session;

	User maria;

	Long leadId;

	@BeforeEach
	void setUp() throws Exception {
		data.resetStatusesAndTags();
		data.clearTelegram();
		telegram.reset();
		maria = data.user("lc-maria", "maria-pass", Role.MANAGER);
		session = TestLogin.as(mvc, "lc-maria", "maria-pass");
		leadId = data.lead(548213097L).getId();
	}

	String json(String s) {
		return s.replace('\'', '"');
	}

	Long statusId(String name) {
		return statuses.findAllByOrderByPositionAsc().stream().filter(s -> s.getName().equals(name)).findFirst()
			.orElseThrow().getId();
	}

	Long tagId(String name) {
		return tags.findAllByOrderByIdAsc().stream().filter(t -> t.getName().equals(name)).findFirst().orElseThrow()
			.getId();
	}

	void connect(Instant lastIncoming) {
		tx.executeWithoutResult(t -> {
			BusinessConnection c = new BusinessConnection("bc1");
			c.setOwnerTelegramUserId(1);
			c.setUserChatId(1);
			c.setCanReply(true);
			c.setEnabled(true);
			c.setUpdatedAt(Instant.now());
			connections.save(c);
			Lead l = leads.findById(leadId).orElseThrow();
			l.setBusinessConnectionId("bc1");
			l.setLastIncomingAt(lastIncoming);
			l.setAwaitingReplySince(lastIncoming);
		});
	}

	@Test
	void changeStatusManagerSourceAndTagsWithHistory() throws Exception {
		mvc.perform(put("/api/leads/{id}/status", leadId).cookie(session).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"statusId\":" + statusId("Квалифицирован") + "}"))
			.andExpect(status().isNoContent());
		mvc.perform(put("/api/leads/{id}/manager", leadId).cookie(session).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"managerId\":" + maria.getId() + "}"))
			.andExpect(status().isNoContent());
		mvc.perform(put("/api/leads/{id}/source", leadId).cookie(session).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(json("{'source':'GROUP'}")))
			.andExpect(status().isNoContent());
		mvc.perform(post("/api/leads/{id}/tags/{tag}", leadId, tagId("Срочно")).cookie(session).with(csrf()))
			.andExpect(status().isNoContent());
		mvc.perform(post("/api/leads/{id}/tags/{tag}", leadId, tagId("Срочно")).cookie(session).with(csrf()))
			.andExpect(status().isNoContent());
		mvc.perform(delete("/api/leads/{id}/tags/{tag}", leadId, tagId("Срочно")).cookie(session).with(csrf()))
			.andExpect(status().isNoContent());
		mvc.perform(put("/api/leads/{id}/manager", leadId).cookie(session).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(json("{'managerId':null}")))
			.andExpect(status().isNoContent());

		mvc.perform(get("/api/leads/{id}/events", leadId).cookie(session))
			.andExpect(jsonPath("$.length()").value(6))
			.andExpect(jsonPath("$[0].type").value("MANAGER_CHANGED"))
			.andExpect(jsonPath("$[0].oldValue").value("lc-maria"))
			.andExpect(jsonPath("$[0].newValue").doesNotExist())
			.andExpect(jsonPath("$[1].type").value("TAG_REMOVED"))
			.andExpect(jsonPath("$[2].type").value("TAG_ADDED"))
			.andExpect(jsonPath("$[2].newValue").value("Срочно"))
			.andExpect(jsonPath("$[3].type").value("SOURCE_CHANGED"))
			.andExpect(jsonPath("$[3].newValue").value("Группа"))
			.andExpect(jsonPath("$[4].type").value("MANAGER_CHANGED"))
			.andExpect(jsonPath("$[5].type").value("STATUS_CHANGED"))
			.andExpect(jsonPath("$[5].oldValue").value("Новый"))
			.andExpect(jsonPath("$[5].newValue").value("Квалифицирован"))
			.andExpect(jsonPath("$[5].actor.displayName").value("lc-maria"));
	}

	@Test
	void cannotAssignBlockedUser() throws Exception {
		User blocked = data.user("lc-blocked", "blocked-pass", Role.MANAGER);
		tx.executeWithoutResult(t -> users.findById(blocked.getId()).orElseThrow().setActive(false));

		mvc.perform(put("/api/leads/{id}/manager", leadId).cookie(session).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"managerId\":" + blocked.getId() + "}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Пользователь заблокирован"));
	}

	@Test
	void replyIsSentOnBehalfOfAccountAndCounted() throws Exception {
		connect(Instant.now().minusSeconds(600));

		mvc.perform(post("/api/leads/{id}/messages", leadId).cookie(session).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(json("{'text':'Добрый день! Подготовлю расчёт.'}")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.direction").value("OUT"))
			.andExpect(jsonPath("$.author.displayName").value("lc-maria"));

		assertThat(telegram.sent).containsExactly("bc1|548213097|Добрый день! Подготовлю расчёт.");
		tx.executeWithoutResult(t -> {
			Lead l = leads.findById(leadId).orElseThrow();
			assertThat(l.getLastMessageText()).isEqualTo("Добрый день! Подготовлю расчёт.");
			assertThat(l.getAwaitingReplySince()).isNull();
			assertThat(responses.findByLeadOrderByAnsweredAtAsc(l)).singleElement()
				.satisfies(r -> assertThat(r.getAuthor().getId()).isEqualTo(maria.getId()));
		});
	}

	@Test
	void replyAfter24HoursIsRejected() throws Exception {
		connect(Instant.now().minusSeconds(25 * 3600));

		mvc.perform(post("/api/leads/{id}/messages", leadId).cookie(session).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(json("{'text':'Привет'}")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message")
				.value("Прошло больше 24 часов с последнего сообщения лида — ответьте ему в Telegram"));
		assertThat(telegram.sent).isEmpty();
	}

	@Test
	void emptyReplyIsInvalid() throws Exception {
		connect(Instant.now());
		mvc.perform(post("/api/leads/{id}/messages", leadId).cookie(session).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(json("{'text':'   '}")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.text").value("Введите сообщение"));
	}

}
```

- [ ] **Step 2: Запустить — падает.**

- [ ] **Step 3: Код**

`leads/LeadCommandService.java`:
```java
package com.vibe.crm.leads;

import java.util.Objects;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.auth.CurrentUser;
import com.vibe.crm.common.ApiException;
import com.vibe.crm.statuses.Status;
import com.vibe.crm.statuses.StatusService;
import com.vibe.crm.tags.Tag;
import com.vibe.crm.tags.TagService;
import com.vibe.crm.users.User;
import com.vibe.crm.users.UserService;

/** Изменения лида менеджером. Каждое реальное изменение пишется в историю. */
@Service
@Transactional
public class LeadCommandService {

	private final LeadQueryService queries;

	private final StatusService statuses;

	private final TagService tags;

	private final UserService users;

	private final LeadHistory history;

	private final ApplicationEventPublisher events;

	public LeadCommandService(LeadQueryService queries, StatusService statuses, TagService tags, UserService users,
			LeadHistory history, ApplicationEventPublisher events) {
		this.queries = queries;
		this.statuses = statuses;
		this.tags = tags;
		this.users = users;
		this.history = history;
		this.events = events;
	}

	public void changeStatus(Long leadId, Long statusId) {
		Lead lead = queries.get(leadId);
		Status status = statuses.get(statusId);
		if (lead.getStatus().getId().equals(status.getId())) {
			return;
		}
		String old = lead.getStatus().getName();
		lead.setStatus(status);
		changed(lead, LeadEventType.STATUS_CHANGED, old, status.getName());
	}

	public void assign(Long leadId, Long managerId) {
		Lead lead = queries.get(leadId);
		User manager = managerId == null ? null : users.get(managerId);
		if (manager != null && !manager.isActive()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Пользователь заблокирован");
		}
		Long oldId = lead.getManager() == null ? null : lead.getManager().getId();
		if (Objects.equals(oldId, managerId)) {
			return;
		}
		String old = lead.getManager() == null ? null : lead.getManager().getDisplayName();
		lead.setManager(manager);
		changed(lead, LeadEventType.MANAGER_CHANGED, old, manager == null ? null : manager.getDisplayName());
	}

	public void changeSource(Long leadId, LeadSource source) {
		Lead lead = queries.get(leadId);
		if (lead.getSource() == source) {
			return;
		}
		String old = LeadSources.label(lead.getSource());
		lead.setSource(source);
		changed(lead, LeadEventType.SOURCE_CHANGED, old, LeadSources.label(source));
	}

	public void addTag(Long leadId, Long tagId) {
		Lead lead = queries.get(leadId);
		Tag tag = tags.get(tagId);
		if (lead.getTags().add(tag)) {
			changed(lead, LeadEventType.TAG_ADDED, null, tag.getName());
		}
	}

	public void removeTag(Long leadId, Long tagId) {
		Lead lead = queries.get(leadId);
		Tag tag = tags.get(tagId);
		if (lead.getTags().remove(tag)) {
			changed(lead, LeadEventType.TAG_REMOVED, tag.getName(), null);
		}
	}

	private void changed(Lead lead, LeadEventType type, String oldValue, String newValue) {
		history.record(lead, CurrentUser.get().id(), type, oldValue, newValue);
		events.publishEvent(new LeadActivity(lead.getId(), LeadActivity.Kind.LEAD_UPDATED));
	}

}
```

`messages/ReplyService.java`:
```java
package com.vibe.crm.messages;

import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.auth.CurrentUser;
import com.vibe.crm.common.ApiException;
import com.vibe.crm.leads.Lead;
import com.vibe.crm.leads.LeadActivity;
import com.vibe.crm.leads.LeadQueryService;
import com.vibe.crm.leads.LeadViews.MessageView;
import com.vibe.crm.telegram.BusinessConnection;
import com.vibe.crm.telegram.BusinessConnectionRepository;
import com.vibe.crm.telegram.TelegramApiException;
import com.vibe.crm.telegram.TelegramBotClient;
import com.vibe.crm.telegram.api.TgMessage;
import com.vibe.crm.users.UserRepository;

/** Ответ лиду из CRM — отправляется в Telegram от имени аккаунта компании. */
@Service
@Transactional
public class ReplyService {

	private final LeadQueryService leads;

	private final BusinessConnectionRepository connections;

	private final TelegramBotClient telegram;

	private final MessageRepository messages;

	private final ResponseTracker responses;

	private final UserRepository users;

	private final ApplicationEventPublisher events;

	public ReplyService(LeadQueryService leads, BusinessConnectionRepository connections, TelegramBotClient telegram,
			MessageRepository messages, ResponseTracker responses, UserRepository users,
			ApplicationEventPublisher events) {
		this.leads = leads;
		this.connections = connections;
		this.telegram = telegram;
		this.messages = messages;
		this.responses = responses;
		this.users = users;
		this.events = events;
	}

	public MessageView reply(Long leadId, String text) {
		Lead lead = leads.get(leadId);
		BusinessConnection connection = lead.getBusinessConnectionId() == null ? null
				: connections.findById(lead.getBusinessConnectionId()).orElse(null);
		String reason = ReplyAvailability.reason(lead, connection, Instant.now());
		if (reason != null) {
			throw ApiException.conflict(reason);
		}
		TgMessage sent;
		try {
			sent = telegram.sendMessage(connection.getId(), lead.getTelegramChatId(), text.trim());
		}
		catch (TelegramApiException e) {
			throw new ApiException(HttpStatus.BAD_GATEWAY, "Telegram не принял сообщение: " + e.description());
		}
		Long authorId = CurrentUser.get().id();
		Instant at = sent.sentAt();
		Message message = messages.save(new Message(lead, sent.messageId(), Direction.OUT, text.trim(), at,
				users.getReferenceById(authorId)));
		lead.registerMessage(message.getText(), at);
		responses.onOutgoing(lead, at, authorId);
		events.publishEvent(new LeadActivity(lead.getId(), LeadActivity.Kind.MESSAGE_CREATED));
		return MessageView.of(message);
	}

}
```

Дописать в `LeadController` (конструктор принимает ещё `LeadCommandService commands`, `ReplyService replies`):

```java
	public record StatusRequest(@jakarta.validation.constraints.NotNull(message = "Выберите статус") Long statusId) {
	}

	public record ManagerRequest(Long managerId) {
	}

	public record SourceRequest(LeadSource source) {
	}

	public record ReplyRequest(
			@jakarta.validation.constraints.NotBlank(message = "Введите сообщение") @jakarta.validation.constraints.Size(
					max = 4096, message = "Сообщение: до 4096 символов") String text) {
	}

	@org.springframework.web.bind.annotation.PutMapping("/{id}/status")
	@org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
	public void status(@PathVariable Long id, @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody StatusRequest body) {
		commands.changeStatus(id, body.statusId());
	}

	@org.springframework.web.bind.annotation.PutMapping("/{id}/manager")
	@org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
	public void manager(@PathVariable Long id, @org.springframework.web.bind.annotation.RequestBody ManagerRequest body) {
		commands.assign(id, body.managerId());
	}

	@org.springframework.web.bind.annotation.PutMapping("/{id}/source")
	@org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
	public void source(@PathVariable Long id, @org.springframework.web.bind.annotation.RequestBody SourceRequest body) {
		commands.changeSource(id, body.source());
	}

	@org.springframework.web.bind.annotation.PostMapping("/{id}/tags/{tagId}")
	@org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
	public void addTag(@PathVariable Long id, @PathVariable Long tagId) {
		commands.addTag(id, tagId);
	}

	@org.springframework.web.bind.annotation.DeleteMapping("/{id}/tags/{tagId}")
	@org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
	public void removeTag(@PathVariable Long id, @PathVariable Long tagId) {
		commands.removeTag(id, tagId);
	}

	@org.springframework.web.bind.annotation.PostMapping("/{id}/messages")
	@org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
	public MessageView reply(@PathVariable Long id, @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody ReplyRequest body) {
		return replies.reply(id, body.text());
	}
```

(При переносе заменить полные имена на импорты.)

- [ ] **Step 4: Тест → `Tests run: 5, Failures: 0`; `./mvnw -q verify`; commit** «Добавить изменение лида и ответ из CRM».

---

### Task 3: Живые обновления (SSE)

**Files:**
- Create: `backend/src/main/java/com/vibe/crm/events/SseHub.java`
- Create: `backend/src/main/java/com/vibe/crm/events/EventsController.java`
- Modify: `backend/src/main/java/com/vibe/crm/CrmApplication.java` (`@EnableScheduling`)
- Test: `backend/src/test/java/com/vibe/crm/events/SseHubTest.java`

**Interfaces:**
- Consumes: `LeadActivity`.
- Produces: `GET /api/events` (`text/event-stream`) — события `name: lead`, `data: {"leadId":N,"kind":"LEAD_CREATED|LEAD_UPDATED|MESSAGE_CREATED"}`; комментарий-пинг каждые 25 с. `SseHub.subscribe() → SseEmitter`, `SseHub.subscribers() → int`.

- [ ] **Step 1: Падающий тест `SseHubTest`** (настоящий сервер — MockMvc не держит поток)

```java
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
```

- [ ] **Step 2: Запустить — падает.**

- [ ] **Step 3: Код**

`events/SseHub.java`:
```java
package com.vibe.crm.events;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.vibe.crm.leads.LeadActivity;

/** Рассылает открытым вкладкам CRM сигналы «у лида что-то изменилось». */
@Component
public class SseHub {

	private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

	public SseEmitter subscribe() {
		SseEmitter emitter = new SseEmitter(0L);
		emitters.add(emitter);
		emitter.onCompletion(() -> emitters.remove(emitter));
		emitter.onTimeout(() -> emitters.remove(emitter));
		emitter.onError(e -> emitters.remove(emitter));
		try {
			emitter.send(SseEmitter.event().comment("connected"));
		}
		catch (IOException e) {
			emitters.remove(emitter);
		}
		return emitter;
	}

	public int subscribers() {
		return emitters.size();
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onActivity(LeadActivity activity) {
		broadcast(SseEmitter.event().name("lead").data(activity));
	}

	/** Пинг, чтобы прокси и браузер не закрывали простаивающее соединение. */
	@Scheduled(fixedDelay = 25_000)
	public void heartbeat() {
		broadcast(SseEmitter.event().comment("ping"));
	}

	private void broadcast(SseEmitter.SseEventBuilder event) {
		for (SseEmitter emitter : emitters) {
			try {
				emitter.send(event);
			}
			catch (IOException | IllegalStateException e) {
				emitters.remove(emitter);
			}
		}
	}

}
```

`events/EventsController.java`:
```java
package com.vibe.crm.events;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class EventsController {

	private final SseHub hub;

	public EventsController(SseHub hub) {
		this.hub = hub;
	}

	@GetMapping(path = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter events() {
		return hub.subscribe();
	}

}
```

В `CrmApplication` добавить `@EnableScheduling` (`org.springframework.scheduling.annotation.EnableScheduling`).

- [ ] **Step 4: Тест → `Tests run: 2, Failures: 0`.** Если поток SSE после первого события отдаётся с ошибкой 401/403 из-за асинхронной диспетчеризации — в `SecurityConfig` разрешить `DispatcherType.ASYNC` и `DispatcherType.ERROR`: `.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()` первой строкой `authorizeHttpRequests`.

- [ ] **Step 5: `./mvnw -q verify`; commit** «Добавить живые обновления для экранов».

---

### Task 4: Дашборд

**Files:**
- Create: `backend/src/main/java/com/vibe/crm/dashboard/{DashboardView,DashboardService,DashboardController}.java`
- Test: `backend/src/test/java/com/vibe/crm/dashboard/DashboardTest.java`

**Interfaces:**
- Produces:
  - `DashboardView(LocalDate from, LocalDate to, List<DayCount> newLeadsByDay, List<StatusCount> byStatus, List<SourceCount> bySource, List<TagCount> byTag, List<ManagerStats> managers)`; `DayCount(LocalDate date, long count)`, `StatusCount(Long statusId, String name, StatusColor color, long count)`, `SourceCount(LeadSource source /*null = не указан*/, long count, int percent)`, `TagCount(Long tagId, String name, TagColor color, long count)`, `ManagerStats(Long managerId /*null = Не назначен*/, String name, long leads, Long deals /*null = нет статуса won*/, Long avgResponseSeconds /*null = нет ответов*/)`.
  - `GET /api/dashboard?from=YYYY-MM-DD&to=YYYY-MM-DD` (по умолчанию последние 30 дней включая сегодня). `from > to` или период > 366 дней → 400.
  - Расчёт по спеке: новые лиды по дням — `created_at` в поясе `crm.timezone`, все дни периода (с нулями); статусы/источники/теги/менеджеры — среди лидов, созданных в периоде; источники с процентами (округление, «не указан» включён, если > 0); статусы и теги — все (с нулями), в порядке настроек; менеджеры — все активные пользователи + «Не назначен» последней строкой; сделки — лиды менеджера в статусе `won`; среднее время ответа — по `response_events.answered_at` в периоде, только с автором.

- [ ] **Step 1: Падающий тест `DashboardTest`**

```java
package com.vibe.crm.dashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.vibe.crm.IntegrationTest;
import com.vibe.crm.TestData;
import com.vibe.crm.TestLogin;
import com.vibe.crm.leads.Lead;
import com.vibe.crm.leads.LeadRepository;
import com.vibe.crm.leads.LeadSource;
import com.vibe.crm.messages.ResponseEvent;
import com.vibe.crm.messages.ResponseEventRepository;
import com.vibe.crm.statuses.StatusRepository;
import com.vibe.crm.tags.TagRepository;
import com.vibe.crm.users.Role;
import com.vibe.crm.users.User;
import com.vibe.crm.users.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

@IntegrationTest
class DashboardTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	LeadRepository leads;

	@Autowired
	StatusRepository statuses;

	@Autowired
	TagRepository tags;

	@Autowired
	UserRepository users;

	@Autowired
	ResponseEventRepository responses;

	@Autowired
	EntityManager em;

	@Autowired
	TransactionTemplate tx;

	Cookie session;

	User maria;

	@BeforeEach
	void setUp() throws Exception {
		data.resetStatusesAndTags();
		data.clearTelegram();
		data.clearUsers();
		maria = data.user("db-maria", "maria-pass", Role.MANAGER);
		session = TestLogin.as(mvc, "db-maria", "maria-pass");
	}

	void lead(long tgId, String createdAt, String status, LeadSource source, boolean mine, String tag) {
		tx.executeWithoutResult(t -> {
			Lead l = new Lead(tgId, tgId, statuses.findAllByOrderByPositionAsc().stream()
				.filter(s -> s.getName().equals(status)).findFirst().orElseThrow(), Instant.parse(createdAt));
			l.setSource(source);
			if (mine) {
				l.setManager(users.findById(maria.getId()).orElseThrow());
			}
			if (tag != null) {
				l.getTags().add(tags.findAllByOrderByIdAsc().stream().filter(x -> x.getName().equals(tag)).findFirst()
					.orElseThrow());
			}
			leads.save(l);
			if (mine) {
				responses.save(new ResponseEvent(l, users.getReferenceById(maria.getId()), tgId == 1 ? 600 : 1200,
						Instant.parse(createdAt).plusSeconds(600)));
			}
		});
	}

	@Test
	void statistics() throws Exception {
		// 13 и 14 октября по Москве; один лид вне периода
		lead(1, "2026-10-13T10:00:00Z", "Сделка", LeadSource.CHANNEL, true, "Опт");
		lead(2, "2026-10-14T10:00:00Z", "Новый", LeadSource.CHANNEL, true, "Опт");
		lead(3, "2026-10-14T11:00:00Z", "Новый", null, false, "VIP");
		lead(4, "2026-10-14T12:00:00Z", "Отказ", LeadSource.GROUP, false, null);
		lead(5, "2026-09-01T10:00:00Z", "Новый", null, false, null);

		mvc.perform(get("/api/dashboard").cookie(session).param("from", "2026-10-13").param("to", "2026-10-14"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.newLeadsByDay.length()").value(2))
			.andExpect(jsonPath("$.newLeadsByDay[0].date").value("2026-10-13"))
			.andExpect(jsonPath("$.newLeadsByDay[0].count").value(1))
			.andExpect(jsonPath("$.newLeadsByDay[1].count").value(3))
			.andExpect(jsonPath("$.byStatus.length()").value(6))
			.andExpect(jsonPath("$.byStatus[0].name").value("Новый"))
			.andExpect(jsonPath("$.byStatus[0].count").value(2))
			.andExpect(jsonPath("$.byStatus[4].count").value(1))
			.andExpect(jsonPath("$.bySource[0].source").value("CHANNEL"))
			.andExpect(jsonPath("$.bySource[0].count").value(2))
			.andExpect(jsonPath("$.bySource[0].percent").value(50))
			.andExpect(jsonPath("$.byTag[1].name").value("Опт"))
			.andExpect(jsonPath("$.byTag[1].count").value(2))
			.andExpect(jsonPath("$.managers[0].name").value("db-maria"))
			.andExpect(jsonPath("$.managers[0].leads").value(2))
			.andExpect(jsonPath("$.managers[0].deals").value(1))
			.andExpect(jsonPath("$.managers[0].avgResponseSeconds").value(900))
			.andExpect(jsonPath("$.managers[1].managerId").doesNotExist())
			.andExpect(jsonPath("$.managers[1].name").value("Не назначен"))
			.andExpect(jsonPath("$.managers[1].leads").value(2));
	}

	@Test
	void invalidPeriod() throws Exception {
		mvc.perform(get("/api/dashboard").cookie(session).param("from", "2026-10-14").param("to", "2026-10-01"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void defaultPeriodIsLast30Days() throws Exception {
		mvc.perform(get("/api/dashboard").cookie(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.newLeadsByDay.length()").value(30));
	}

}
```

- [ ] **Step 2: Запустить — падает.**

- [ ] **Step 3: Код**

`dashboard/DashboardView.java`:
```java
package com.vibe.crm.dashboard;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vibe.crm.leads.LeadSource;
import com.vibe.crm.statuses.StatusColor;
import com.vibe.crm.tags.TagColor;

public record DashboardView(LocalDate from, LocalDate to, List<DayCount> newLeadsByDay, List<StatusCount> byStatus,
		List<SourceCount> bySource, List<TagCount> byTag, List<ManagerStats> managers) {

	public record DayCount(LocalDate date, long count) {
	}

	public record StatusCount(Long statusId, String name, StatusColor color, long count) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SourceCount(LeadSource source, long count, int percent) {
	}

	public record TagCount(Long tagId, String name, TagColor color, long count) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ManagerStats(Long managerId, String name, long leads, Long deals, Long avgResponseSeconds) {
	}

}
```

`dashboard/DashboardService.java`:
```java
package com.vibe.crm.dashboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.common.ApiException;
import com.vibe.crm.dashboard.DashboardView.DayCount;
import com.vibe.crm.dashboard.DashboardView.ManagerStats;
import com.vibe.crm.dashboard.DashboardView.SourceCount;
import com.vibe.crm.dashboard.DashboardView.StatusCount;
import com.vibe.crm.dashboard.DashboardView.TagCount;
import com.vibe.crm.leads.LeadSource;
import com.vibe.crm.statuses.Status;
import com.vibe.crm.statuses.StatusRepository;
import com.vibe.crm.tags.TagRepository;
import com.vibe.crm.users.User;
import com.vibe.crm.users.UserRepository;

import jakarta.persistence.EntityManager;

@Service
@Transactional(readOnly = true)
public class DashboardService {

	private final EntityManager em;

	private final StatusRepository statuses;

	private final TagRepository tags;

	private final UserRepository users;

	private final ZoneId zone;

	public DashboardService(EntityManager em, StatusRepository statuses, TagRepository tags, UserRepository users,
			@Value("${crm.timezone:Europe/Moscow}") String timezone) {
		this.em = em;
		this.statuses = statuses;
		this.tags = tags;
		this.users = users;
		this.zone = ZoneId.of(timezone);
	}

	public LocalDate today() {
		return LocalDate.now(zone);
	}

	public DashboardView build(LocalDate from, LocalDate to) {
		if (from.isAfter(to)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Дата «с» позже даты «по»");
		}
		if (ChronoUnit.DAYS.between(from, to) > 366) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Период не может быть длиннее года");
		}
		Instant start = from.atStartOfDay(zone).toInstant();
		Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
		return new DashboardView(from, to, byDay(from, to, start, end), byStatus(start, end), bySource(start, end),
				byTag(start, end), managers(start, end));
	}

	@SuppressWarnings("unchecked")
	private List<Object[]> rows(String sql, Instant start, Instant end) {
		return em.createNativeQuery(sql).setParameter("start", start).setParameter("end", end).getResultList();
	}

	private static long num(Object o) {
		return o == null ? 0 : ((Number) o).longValue();
	}

	private List<DayCount> byDay(LocalDate from, LocalDate to, Instant start, Instant end) {
		Map<LocalDate, Long> counts = new HashMap<>();
		for (Object[] r : rows("select cast(created_at at time zone '" + zone.getId() + "' as date), count(*) from leads"
				+ " where created_at >= :start and created_at < :end group by 1", start, end)) {
			counts.put(((java.sql.Date) r[0]).toLocalDate(), num(r[1]));
		}
		List<DayCount> days = new ArrayList<>();
		for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
			days.add(new DayCount(d, counts.getOrDefault(d, 0L)));
		}
		return days;
	}

	private List<StatusCount> byStatus(Instant start, Instant end) {
		Map<Long, Long> counts = new HashMap<>();
		for (Object[] r : rows("select status_id, count(*) from leads where created_at >= :start and created_at < :end"
				+ " group by status_id", start, end)) {
			counts.put(num(r[0]), num(r[1]));
		}
		return statuses.findAllByOrderByPositionAsc().stream()
			.map(s -> new StatusCount(s.getId(), s.getName(), s.getColor(), counts.getOrDefault(s.getId(), 0L)))
			.toList();
	}

	private List<SourceCount> bySource(Instant start, Instant end) {
		Map<LeadSource, Long> counts = new HashMap<>();
		long total = 0;
		for (Object[] r : rows("select source, count(*) from leads where created_at >= :start and created_at < :end"
				+ " group by source", start, end)) {
			LeadSource source = r[0] == null ? null : LeadSource.valueOf((String) r[0]);
			counts.put(source, num(r[1]));
			total += num(r[1]);
		}
		List<SourceCount> result = new ArrayList<>();
		List<LeadSource> order = new ArrayList<>(List.of(LeadSource.values()));
		order.add(null);
		for (LeadSource s : order) {
			long c = counts.getOrDefault(s, 0L);
			if (s == null && c == 0) {
				continue;
			}
			int percent = total == 0 ? 0 : (int) Math.round(c * 100.0 / total);
			result.add(new SourceCount(s, c, percent));
		}
		result.sort((a, b) -> Long.compare(b.count(), a.count()));
		return result;
	}

	private List<TagCount> byTag(Instant start, Instant end) {
		Map<Long, Long> counts = new HashMap<>();
		for (Object[] r : rows("select lt.tag_id, count(*) from lead_tags lt join leads l on l.id = lt.lead_id"
				+ " where l.created_at >= :start and l.created_at < :end group by lt.tag_id", start, end)) {
			counts.put(num(r[0]), num(r[1]));
		}
		return tags.findAllByOrderByIdAsc().stream()
			.map(t -> new TagCount(t.getId(), t.getName(), t.getColor(), counts.getOrDefault(t.getId(), 0L)))
			.toList();
	}

	private List<ManagerStats> managers(Instant start, Instant end) {
		Long wonId = statuses.findAllByOrderByPositionAsc().stream().filter(Status::isWon).map(Status::getId)
			.findFirst().orElse(null);
		Map<Long, long[]> leadStats = new HashMap<>();
		for (Object[] r : rows("select manager_id, count(*), count(*) filter (where status_id = "
				+ (wonId == null ? "-1" : wonId) + ") from leads where created_at >= :start and created_at < :end"
				+ " group by manager_id", start, end)) {
			leadStats.put(r[0] == null ? null : num(r[0]), new long[] { num(r[1]), num(r[2]) });
		}
		Map<Long, Long> avg = new HashMap<>();
		for (Object[] r : rows("select author_id, avg(waited_seconds) from response_events where author_id is not null"
				+ " and answered_at >= :start and answered_at < :end group by author_id", start, end)) {
			avg.put(num(r[0]), ((BigDecimal) r[1]).setScale(0, java.math.RoundingMode.HALF_UP).longValue());
		}
		List<ManagerStats> result = new ArrayList<>();
		for (User u : users.findByActiveTrueOrderByDisplayNameAsc()) {
			long[] s = leadStats.getOrDefault(u.getId(), new long[] { 0, 0 });
			result.add(new ManagerStats(u.getId(), u.getDisplayName(), s[0], wonId == null ? null : s[1],
					avg.get(u.getId())));
		}
		long[] none = leadStats.getOrDefault(null, new long[] { 0, 0 });
		result.add(new ManagerStats(null, "Не назначен", none[0], null, null));
		return result.stream().filter(Objects::nonNull).toList();
	}

}
```

`dashboard/DashboardController.java`:
```java
package com.vibe.crm.dashboard;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

	private final DashboardService service;

	public DashboardController(DashboardService service) {
		this.service = service;
	}

	@GetMapping
	public DashboardView dashboard(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		LocalDate end = to == null ? service.today() : to;
		LocalDate start = from == null ? end.minusDays(29) : from;
		return service.build(start, end);
	}

}
```

- [ ] **Step 4: Тест → `Tests run: 3, Failures: 0`.** Возможные расхождения типов в native-запросах (`java.sql.Date` vs `LocalDate`, `BigDecimal` vs `Double`) — привести через `Number`/`toString`, не меняя смысла.

- [ ] **Step 5: `./mvnw -q verify`; commit** «Добавить статистику для дашборда»; push в `dev`.
