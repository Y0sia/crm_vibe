# Этап 2. Бекенд: статусы, теги, хранение лидов и истории — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Таблицы лидов, тегов лида и истории изменений; настраиваемые статусы (воронка) и теги с API, где удаление статуса переводит лидов в первый статус, а удаление тега снимает его с лидов — с записью в историю.

**Architecture:** Пакеты `com.vibe.crm.statuses`, `com.vibe.crm.tags`, `com.vibe.crm.leads`. Сид статусов/тегов — Liquibase `insert`. Изменения доступны только `ADMIN` через `@PreAuthorize`; чтение — всем вошедшим.

**Tech Stack:** Spring Boot 4.1.1, Spring Data JPA, Spring Security method security, Liquibase, Testcontainers.

Спека: `docs/superpowers/specs/2026-10-14-telegram-leads-crm-design.md`. Предыдущий этап: `docs/superpowers/plans/2026-10-14-01-backend-auth-users.md`.

## Global Constraints

- Всё из Global Constraints этапа 1 (Boot 4, Liquibase-only, `/api`, ошибки `{"message","fields"}` на русском, коммиты без упоминаний ИИ).
- Тесты: `@IntegrationTest` (общий контейнер БД на прогон — каждый тест сам очищает нужные таблицы в `@BeforeEach`), вход — `TestLogin.as(mvc, login, password)` → `Cookie`, POST/PATCH/PUT/DELETE — `.with(csrf())`.
- Палитра статусов (ключи API): `BLUE, ORANGE, PURPLE, TEAL, GREEN, PINK, YELLOW, GRAY`. Палитра тегов: `BRICK, BLUE, PURPLE, GREEN, RED, TEAL, MUSTARD, GRAPHITE`. Конкретные цвета — на фронтенде (из `design.html`).
- Сид статусов (по порядку): Новый BLUE, В работе ORANGE, Ждёт ответа PURPLE, Квалифицирован TEAL, Сделка GREEN (`won=true`), Отказ GRAY. Сид тегов: VIP BRICK, Опт BLUE, Розница PURPLE, Повторный GREEN, Срочно RED.
- Источники лида (`LeadSource`): `CHANNEL, GROUP, ADS, REFERRAL, OTHER`; `null` = не указан.

---

### Task 1: Таблицы статусов, тегов, лидов и истории + сущности

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/004-create-statuses-and-tags.yaml`
- Create: `backend/src/main/resources/db/changelog/changes/005-create-leads.yaml`
- Create: `backend/src/main/java/com/vibe/crm/statuses/{StatusColor,Status,StatusRepository}.java`
- Create: `backend/src/main/java/com/vibe/crm/tags/{TagColor,Tag,TagRepository}.java`
- Create: `backend/src/main/java/com/vibe/crm/leads/{LeadSource,Lead,LeadRepository,LeadEventType,LeadEvent,LeadEventRepository,LeadHistory}.java`
- Test: `backend/src/test/java/com/vibe/crm/leads/LeadStorageTest.java`

**Interfaces:**
- Produces:
  - `Status`: `Long getId()`, `String getName()`, `void setName(String)`, `StatusColor getColor()`, `void setColor(StatusColor)`, `int getPosition()`, `void setPosition(int)`, `boolean isWon()`; конструкторы `Status(String name, StatusColor color, int position)` и `Status(String name, StatusColor color, int position, boolean won)`.
  - `StatusRepository extends JpaRepository<Status, Long>`: `List<Status> findAllByOrderByPositionAsc()`, `Optional<Status> findFirstByOrderByPositionAsc()`, `boolean existsByNameIgnoreCase(String)`, `boolean existsByNameIgnoreCaseAndIdNot(String, Long)`.
  - `Tag`: `Long getId()`, `String getName()`, `void setName(String)`, `TagColor getColor()`, `void setColor(TagColor)`; конструктор `Tag(String name, TagColor color)`.
  - `TagRepository extends JpaRepository<Tag, Long>`: `List<Tag> findAllByOrderByIdAsc()`, `boolean existsByNameIgnoreCase(String)`, `boolean existsByNameIgnoreCaseAndIdNot(String, Long)`.
  - `Lead`: конструктор `Lead(long telegramUserId, long telegramChatId, Status status, Instant createdAt)`; геттеры/сеттеры: `id`, `telegramUserId`, `telegramChatId`, `businessConnectionId`, `firstName`, `lastName`, `username`, `phone`, `status`, `manager` (`User`, nullable), `source` (`LeadSource`, nullable), `createdAt`, `lastMessageAt`, `lastMessageText`, `lastIncomingAt`, `awaitingReplySince`; `Set<Tag> getTags()`; `String displayName()` — «Имя Фамилия», иначе `@username`, иначе `ID <telegramUserId>`.
  - `LeadRepository extends JpaRepository<Lead, Long>, JpaSpecificationExecutor<Lead>`: `Optional<Lead> findByTelegramUserId(long)`, `Optional<Lead> findByTelegramChatId(long)`, `List<Lead> findByStatus(Status)`, `List<Lead> findByTagsContains(Tag)`, `List<Object[]> countByStatus()` (`[statusId, count]`), `List<Object[]> countByTag()` (`[tagId, count]`), `long countByStatus(Status)`.
  - `enum LeadEventType { CREATED, STATUS_CHANGED, MANAGER_CHANGED, SOURCE_CHANGED, TAG_ADDED, TAG_REMOVED }`
  - `LeadEvent`: `Long getId()`, `Lead getLead()`, `User getActor()` (nullable), `LeadEventType getType()`, `String getOldValue()`, `String getNewValue()`, `Instant getCreatedAt()`.
  - `LeadEventRepository extends JpaRepository<LeadEvent, Long>`: `List<LeadEvent> findByLeadOrderByCreatedAtDescIdDesc(Lead)`.
  - `LeadHistory` (`@Service`): `void record(Lead lead, Long actorId, LeadEventType type, String oldValue, String newValue)` — `actorId == null` → системное событие.

- [ ] **Step 1: Написать падающий тест `LeadStorageTest`**

```java
package com.vibe.crm.leads;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.IntegrationTest;
import com.vibe.crm.statuses.Status;
import com.vibe.crm.statuses.StatusRepository;
import com.vibe.crm.tags.Tag;
import com.vibe.crm.tags.TagRepository;

@IntegrationTest
@Transactional
class LeadStorageTest {

	@Autowired
	StatusRepository statuses;

	@Autowired
	TagRepository tags;

	@Autowired
	LeadRepository leads;

	@Autowired
	LeadHistory history;

	@Autowired
	LeadEventRepository events;

	@BeforeEach
	void clean() {
		leads.deleteAll();
	}

	@Test
	void seedStatusesAndTagsExist() {
		assertThat(statuses.findAllByOrderByPositionAsc()).extracting(Status::getName)
			.containsExactly("Новый", "В работе", "Ждёт ответа", "Квалифицирован", "Сделка", "Отказ");
		assertThat(statuses.findAllByOrderByPositionAsc()).filteredOn(Status::isWon)
			.extracting(Status::getName).containsExactly("Сделка");
		assertThat(tags.findAllByOrderByIdAsc()).extracting(Tag::getName)
			.containsExactly("VIP", "Опт", "Розница", "Повторный", "Срочно");
	}

	@Test
	void leadWithTagsAndHistoryIsStored() {
		Status first = statuses.findFirstByOrderByPositionAsc().orElseThrow();
		Tag vip = tags.findAllByOrderByIdAsc().getFirst();
		Lead lead = new Lead(548213097L, 548213097L, first, Instant.parse("2026-10-13T12:47:00Z"));
		lead.setFirstName("Дмитрий");
		lead.setLastName("Орлов");
		lead.setUsername("orlov_d");
		lead.getTags().add(vip);
		leads.save(lead);

		history.record(lead, null, LeadEventType.CREATED, null, null);

		Lead found = leads.findByTelegramUserId(548213097L).orElseThrow();
		assertThat(found.displayName()).isEqualTo("Дмитрий Орлов");
		assertThat(found.getTags()).containsExactly(vip);
		assertThat(leads.countByStatus(first)).isEqualTo(1);
		assertThat(events.findByLeadOrderByCreatedAtDescIdDesc(found)).extracting(LeadEvent::getType)
			.containsExactly(LeadEventType.CREATED);
	}

	@Test
	void displayNameFallsBackToUsernameThenId() {
		Status first = statuses.findFirstByOrderByPositionAsc().orElseThrow();
		Lead lead = new Lead(1L, 1L, first, Instant.now());
		assertThat(lead.displayName()).isEqualTo("ID 1");
		lead.setUsername("anna_sm");
		assertThat(lead.displayName()).isEqualTo("@anna_sm");
	}

}
```

- [ ] **Step 2: Запустить — падает компиляция** (`./mvnw -q test -Dtest=LeadStorageTest`).

- [ ] **Step 3: Миграция `004-create-statuses-and-tags.yaml`**

```yaml
databaseChangeLog:
  - changeSet:
      id: 004-create-statuses-and-tags
      author: crm
      changes:
        - createTable:
            tableName: statuses
            columns:
              - column:
                  name: id
                  type: bigint
                  autoIncrement: true
                  constraints:
                    primaryKey: true
                    primaryKeyName: pk_statuses
              - column:
                  name: name
                  type: varchar(64)
                  constraints:
                    nullable: false
                    unique: true
                    uniqueConstraintName: uq_statuses_name
              - column:
                  name: color
                  type: varchar(16)
                  constraints:
                    nullable: false
              - column:
                  name: position
                  type: int
                  constraints:
                    nullable: false
              - column:
                  name: won
                  type: boolean
                  defaultValueBoolean: false
                  constraints:
                    nullable: false
        - createTable:
            tableName: tags
            columns:
              - column:
                  name: id
                  type: bigint
                  autoIncrement: true
                  constraints:
                    primaryKey: true
                    primaryKeyName: pk_tags
              - column:
                  name: name
                  type: varchar(64)
                  constraints:
                    nullable: false
                    unique: true
                    uniqueConstraintName: uq_tags_name
              - column:
                  name: color
                  type: varchar(16)
                  constraints:
                    nullable: false
  - changeSet:
      id: 004-create-statuses-and-tags-seed
      author: crm
      changes:
        - insert: { tableName: statuses, columns: [ { column: { name: name, value: Новый } }, { column: { name: color, value: BLUE } }, { column: { name: position, valueNumeric: 0 } } ] }
        - insert: { tableName: statuses, columns: [ { column: { name: name, value: В работе } }, { column: { name: color, value: ORANGE } }, { column: { name: position, valueNumeric: 1 } } ] }
        - insert: { tableName: statuses, columns: [ { column: { name: name, value: Ждёт ответа } }, { column: { name: color, value: PURPLE } }, { column: { name: position, valueNumeric: 2 } } ] }
        - insert: { tableName: statuses, columns: [ { column: { name: name, value: Квалифицирован } }, { column: { name: color, value: TEAL } }, { column: { name: position, valueNumeric: 3 } } ] }
        - insert: { tableName: statuses, columns: [ { column: { name: name, value: Сделка } }, { column: { name: color, value: GREEN } }, { column: { name: position, valueNumeric: 4 } }, { column: { name: won, valueBoolean: true } } ] }
        - insert: { tableName: statuses, columns: [ { column: { name: name, value: Отказ } }, { column: { name: color, value: GRAY } }, { column: { name: position, valueNumeric: 5 } } ] }
        - insert: { tableName: tags, columns: [ { column: { name: name, value: VIP } }, { column: { name: color, value: BRICK } } ] }
        - insert: { tableName: tags, columns: [ { column: { name: name, value: Опт } }, { column: { name: color, value: BLUE } } ] }
        - insert: { tableName: tags, columns: [ { column: { name: name, value: Розница } }, { column: { name: color, value: PURPLE } } ] }
        - insert: { tableName: tags, columns: [ { column: { name: name, value: Повторный } }, { column: { name: color, value: GREEN } } ] }
        - insert: { tableName: tags, columns: [ { column: { name: name, value: Срочно } }, { column: { name: color, value: RED } } ] }
      rollback:
        - delete: { tableName: tags }
        - delete: { tableName: statuses }
```

- [ ] **Step 4: Миграция `005-create-leads.yaml`**

```yaml
databaseChangeLog:
  - changeSet:
      id: 005-create-leads
      author: crm
      changes:
        - createTable:
            tableName: leads
            columns:
              - column: { name: id, type: bigint, autoIncrement: true, constraints: { primaryKey: true, primaryKeyName: pk_leads } }
              - column: { name: telegram_user_id, type: bigint, constraints: { nullable: false, unique: true, uniqueConstraintName: uq_leads_telegram_user_id } }
              - column: { name: telegram_chat_id, type: bigint, constraints: { nullable: false } }
              - column: { name: business_connection_id, type: varchar(128) }
              - column: { name: first_name, type: varchar(255) }
              - column: { name: last_name, type: varchar(255) }
              - column: { name: username, type: varchar(64) }
              - column: { name: phone, type: varchar(32) }
              - column: { name: status_id, type: bigint, constraints: { nullable: false, foreignKeyName: fk_leads_status, references: statuses(id) } }
              - column: { name: manager_id, type: bigint, constraints: { foreignKeyName: fk_leads_manager, references: users(id) } }
              - column: { name: source, type: varchar(16) }
              - column: { name: created_at, type: timestamp with time zone, constraints: { nullable: false } }
              - column: { name: last_message_at, type: timestamp with time zone }
              - column: { name: last_message_text, type: text }
              - column: { name: last_incoming_at, type: timestamp with time zone }
              - column: { name: awaiting_reply_since, type: timestamp with time zone }
        - createIndex: { tableName: leads, indexName: idx_leads_telegram_chat_id, columns: [ { column: { name: telegram_chat_id } } ] }
        - createIndex: { tableName: leads, indexName: idx_leads_status_id, columns: [ { column: { name: status_id } } ] }
        - createIndex: { tableName: leads, indexName: idx_leads_manager_id, columns: [ { column: { name: manager_id } } ] }
        - createIndex: { tableName: leads, indexName: idx_leads_last_message_at, columns: [ { column: { name: last_message_at } } ] }
        - createIndex: { tableName: leads, indexName: idx_leads_created_at, columns: [ { column: { name: created_at } } ] }
        - createTable:
            tableName: lead_tags
            columns:
              - column: { name: lead_id, type: bigint, constraints: { nullable: false, foreignKeyName: fk_lead_tags_lead, references: leads(id), deleteCascade: true } }
              - column: { name: tag_id, type: bigint, constraints: { nullable: false, foreignKeyName: fk_lead_tags_tag, references: tags(id), deleteCascade: true } }
        - addPrimaryKey: { tableName: lead_tags, columnNames: 'lead_id, tag_id', constraintName: pk_lead_tags }
        - createIndex: { tableName: lead_tags, indexName: idx_lead_tags_tag_id, columns: [ { column: { name: tag_id } } ] }
        - createTable:
            tableName: lead_events
            columns:
              - column: { name: id, type: bigint, autoIncrement: true, constraints: { primaryKey: true, primaryKeyName: pk_lead_events } }
              - column: { name: lead_id, type: bigint, constraints: { nullable: false, foreignKeyName: fk_lead_events_lead, references: leads(id), deleteCascade: true } }
              - column: { name: actor_id, type: bigint, constraints: { foreignKeyName: fk_lead_events_actor, references: users(id) } }
              - column: { name: type, type: varchar(32), constraints: { nullable: false } }
              - column: { name: old_value, type: varchar(255) }
              - column: { name: new_value, type: varchar(255) }
              - column: { name: created_at, type: timestamp with time zone, constraints: { nullable: false } }
        - createIndex: { tableName: lead_events, indexName: idx_lead_events_lead_id, columns: [ { column: { name: lead_id } } ] }
```

- [ ] **Step 5: Сущности статусов**

`statuses/StatusColor.java`:
```java
package com.vibe.crm.statuses;

public enum StatusColor {
	BLUE, ORANGE, PURPLE, TEAL, GREEN, PINK, YELLOW, GRAY
}
```

`statuses/Status.java`:
```java
package com.vibe.crm.statuses;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Этап воронки. Порядок задаёт position; won отмечает статус «Сделка» для статистики. */
@Entity
@Table(name = "statuses")
public class Status {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 64, unique = true)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private StatusColor color;

	@Column(nullable = false)
	private int position;

	@Column(nullable = false)
	private boolean won;

	protected Status() {
	}

	public Status(String name, StatusColor color, int position) {
		this.name = name.trim();
		this.color = color;
		this.position = position;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name.trim();
	}

	public StatusColor getColor() {
		return color;
	}

	public void setColor(StatusColor color) {
		this.color = color;
	}

	public int getPosition() {
		return position;
	}

	public void setPosition(int position) {
		this.position = position;
	}

	public boolean isWon() {
		return won;
	}

}
```

`statuses/StatusRepository.java`:
```java
package com.vibe.crm.statuses;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StatusRepository extends JpaRepository<Status, Long> {

	List<Status> findAllByOrderByPositionAsc();

	Optional<Status> findFirstByOrderByPositionAsc();

	boolean existsByNameIgnoreCase(String name);

	boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

}
```

- [ ] **Step 6: Сущности тегов**

`tags/TagColor.java`:
```java
package com.vibe.crm.tags;

public enum TagColor {
	BRICK, BLUE, PURPLE, GREEN, RED, TEAL, MUSTARD, GRAPHITE
}
```

`tags/Tag.java`:
```java
package com.vibe.crm.tags;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "tags")
public class Tag {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 64, unique = true)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private TagColor color;

	protected Tag() {
	}

	public Tag(String name, TagColor color) {
		this.name = name.trim();
		this.color = color;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name.trim();
	}

	public TagColor getColor() {
		return color;
	}

	public void setColor(TagColor color) {
		this.color = color;
	}

}
```

`tags/TagRepository.java`:
```java
package com.vibe.crm.tags;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, Long> {

	List<Tag> findAllByOrderByIdAsc();

	boolean existsByNameIgnoreCase(String name);

	boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

}
```

- [ ] **Step 7: Сущности лидов и истории**

`leads/LeadSource.java`:
```java
package com.vibe.crm.leads;

/** Откуда пришёл лид. Указывается менеджером вручную; null — не указан. */
public enum LeadSource {
	CHANNEL, GROUP, ADS, REFERRAL, OTHER
}
```

`leads/Lead.java`:
```java
package com.vibe.crm.leads;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import com.vibe.crm.statuses.Status;
import com.vibe.crm.tags.Tag;
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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Человек, написавший в личку рабочему Telegram-аккаунту. */
@Entity
@Table(name = "leads")
public class Lead {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "telegram_user_id", nullable = false, unique = true)
	private long telegramUserId;

	@Column(name = "telegram_chat_id", nullable = false)
	private long telegramChatId;

	@Column(name = "business_connection_id", length = 128)
	private String businessConnectionId;

	@Column(name = "first_name")
	private String firstName;

	@Column(name = "last_name")
	private String lastName;

	@Column(length = 64)
	private String username;

	@Column(length = 32)
	private String phone;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "status_id", nullable = false)
	private Status status;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "manager_id")
	private User manager;

	@Enumerated(EnumType.STRING)
	@Column(length = 16)
	private LeadSource source;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "last_message_at")
	private Instant lastMessageAt;

	@Column(name = "last_message_text", columnDefinition = "text")
	private String lastMessageText;

	@Column(name = "last_incoming_at")
	private Instant lastIncomingAt;

	@Column(name = "awaiting_reply_since")
	private Instant awaitingReplySince;

	@ManyToMany
	@JoinTable(name = "lead_tags", joinColumns = @JoinColumn(name = "lead_id"),
			inverseJoinColumns = @JoinColumn(name = "tag_id"))
	private Set<Tag> tags = new HashSet<>();

	protected Lead() {
	}

	public Lead(long telegramUserId, long telegramChatId, Status status, Instant createdAt) {
		this.telegramUserId = telegramUserId;
		this.telegramChatId = telegramChatId;
		this.status = status;
		this.createdAt = createdAt;
	}

	/** «Имя Фамилия», иначе @username, иначе ID. */
	public String displayName() {
		String full = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
		if (!full.isEmpty()) {
			return full;
		}
		if (username != null && !username.isBlank()) {
			return "@" + username;
		}
		return "ID " + telegramUserId;
	}

	public Long getId() {
		return id;
	}

	public long getTelegramUserId() {
		return telegramUserId;
	}

	public long getTelegramChatId() {
		return telegramChatId;
	}

	public void setTelegramChatId(long telegramChatId) {
		this.telegramChatId = telegramChatId;
	}

	public String getBusinessConnectionId() {
		return businessConnectionId;
	}

	public void setBusinessConnectionId(String businessConnectionId) {
		this.businessConnectionId = businessConnectionId;
	}

	public String getFirstName() {
		return firstName;
	}

	public void setFirstName(String firstName) {
		this.firstName = firstName;
	}

	public String getLastName() {
		return lastName;
	}

	public void setLastName(String lastName) {
		this.lastName = lastName;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPhone() {
		return phone;
	}

	public void setPhone(String phone) {
		this.phone = phone;
	}

	public Status getStatus() {
		return status;
	}

	public void setStatus(Status status) {
		this.status = status;
	}

	public User getManager() {
		return manager;
	}

	public void setManager(User manager) {
		this.manager = manager;
	}

	public LeadSource getSource() {
		return source;
	}

	public void setSource(LeadSource source) {
		this.source = source;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getLastMessageAt() {
		return lastMessageAt;
	}

	public void setLastMessageAt(Instant lastMessageAt) {
		this.lastMessageAt = lastMessageAt;
	}

	public String getLastMessageText() {
		return lastMessageText;
	}

	public void setLastMessageText(String lastMessageText) {
		this.lastMessageText = lastMessageText;
	}

	public Instant getLastIncomingAt() {
		return lastIncomingAt;
	}

	public void setLastIncomingAt(Instant lastIncomingAt) {
		this.lastIncomingAt = lastIncomingAt;
	}

	public Instant getAwaitingReplySince() {
		return awaitingReplySince;
	}

	public void setAwaitingReplySince(Instant awaitingReplySince) {
		this.awaitingReplySince = awaitingReplySince;
	}

	public Set<Tag> getTags() {
		return tags;
	}

}
```

`leads/LeadRepository.java`:
```java
package com.vibe.crm.leads;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import com.vibe.crm.statuses.Status;
import com.vibe.crm.tags.Tag;

public interface LeadRepository extends JpaRepository<Lead, Long>, JpaSpecificationExecutor<Lead> {

	Optional<Lead> findByTelegramUserId(long telegramUserId);

	Optional<Lead> findByTelegramChatId(long telegramChatId);

	List<Lead> findByStatus(Status status);

	List<Lead> findByTagsContains(Tag tag);

	long countByStatus(Status status);

	/** [statusId, count] по всем лидам. */
	@Query("select l.status.id, count(l) from Lead l group by l.status.id")
	List<Object[]> countByStatus();

	/** [tagId, count] по всем лидам. */
	@Query("select t.id, count(l) from Lead l join l.tags t group by t.id")
	List<Object[]> countByTag();

}
```

`leads/LeadEventType.java`:
```java
package com.vibe.crm.leads;

public enum LeadEventType {
	CREATED, STATUS_CHANGED, MANAGER_CHANGED, SOURCE_CHANGED, TAG_ADDED, TAG_REMOVED
}
```

`leads/LeadEvent.java`:
```java
package com.vibe.crm.leads;

import java.time.Instant;

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

/**
 * Запись истории лида. Значения хранятся текстом (имена на момент события),
 * чтобы история читалась после переименования или удаления статусов и тегов.
 */
@Entity
@Table(name = "lead_events")
public class LeadEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "lead_id", nullable = false)
	private Lead lead;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "actor_id")
	private User actor;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private LeadEventType type;

	@Column(name = "old_value")
	private String oldValue;

	@Column(name = "new_value")
	private String newValue;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected LeadEvent() {
	}

	public LeadEvent(Lead lead, User actor, LeadEventType type, String oldValue, String newValue, Instant createdAt) {
		this.lead = lead;
		this.actor = actor;
		this.type = type;
		this.oldValue = oldValue;
		this.newValue = newValue;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public Lead getLead() {
		return lead;
	}

	public User getActor() {
		return actor;
	}

	public LeadEventType getType() {
		return type;
	}

	public String getOldValue() {
		return oldValue;
	}

	public String getNewValue() {
		return newValue;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
```

`leads/LeadEventRepository.java`:
```java
package com.vibe.crm.leads;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadEventRepository extends JpaRepository<LeadEvent, Long> {

	List<LeadEvent> findByLeadOrderByCreatedAtDescIdDesc(Lead lead);

}
```

`leads/LeadHistory.java`:
```java
package com.vibe.crm.leads;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.users.UserRepository;

@Service
public class LeadHistory {

	private final LeadEventRepository events;

	private final UserRepository users;

	public LeadHistory(LeadEventRepository events, UserRepository users) {
		this.events = events;
		this.users = users;
	}

	/** actorId == null — событие системы (например, лид создан из Telegram). */
	@Transactional
	public void record(Lead lead, Long actorId, LeadEventType type, String oldValue, String newValue) {
		events.save(new LeadEvent(lead, actorId == null ? null : users.getReferenceById(actorId), type, oldValue,
				newValue, Instant.now()));
	}

}
```

- [ ] **Step 8: Запустить тест** — `./mvnw -q test -Dtest=LeadStorageTest` → `Tests run: 3, Failures: 0`.

- [ ] **Step 9: `./mvnw -q verify` зелёный, commit** — «Добавить таблицы статусов, тегов, лидов и истории».

---

### Task 2: API статусов

**Files:**
- Modify: `backend/src/main/java/com/vibe/crm/auth/SecurityConfig.java` — `@EnableMethodSecurity`
- Create: `backend/src/main/java/com/vibe/crm/statuses/{StatusDto,StatusService,StatusController}.java`
- Test: `backend/src/test/java/com/vibe/crm/statuses/StatusControllerTest.java`
- Test: `backend/src/test/java/com/vibe/crm/TestData.java` — общий хелпер тестовых данных

**Interfaces:**
- Consumes: Task 1, `ApiException`, `CurrentUser`, `LeadHistory`.
- Produces:
  - `record StatusDto(Long id, String name, StatusColor color, int position, boolean won, long leadCount)`.
  - `StatusService`: `List<StatusDto> list()`, `StatusDto create(String name, StatusColor color)`, `StatusDto update(Long id, String name, StatusColor color)`, `List<StatusDto> reorder(List<Long> ids)`, `void delete(Long id)`, `Status first()` (первый по порядку, для новых лидов), `Status get(Long id)`.
  - HTTP: `GET /api/statuses`; `POST /api/statuses {name,color}` → 201 (ADMIN); `PATCH /api/statuses/{id} {name,color}` (ADMIN); `PUT /api/statuses/order {ids}` (ADMIN); `DELETE /api/statuses/{id}` → 204 (ADMIN).
  - Ошибки: дубликат имени → 409 «Статус с таким названием уже есть»; удаление последнего → 409 «Нельзя удалить единственный статус»; порядок не со всеми id → 400 «Передайте все статусы в новом порядке».
  - `TestData` (`@Component` в тестах): `User user(String login, String password, Role role)` (создаёт, если нет), `Lead lead(long telegramUserId)` (в первом статусе), `void clearLeads()`, `void clearUsers()` (сначала лиды и история — они ссылаются на пользователей), `void resetStatusesAndTags()` — удаляет лидов, затем все статусы/теги и вставляет сид заново (тем же содержимым, что миграция).

- [ ] **Step 1: Хелпер `TestData.java`**

```java
package com.vibe.crm;

import java.time.Instant;
import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.leads.Lead;
import com.vibe.crm.leads.LeadEventRepository;
import com.vibe.crm.leads.LeadRepository;
import com.vibe.crm.statuses.Status;
import com.vibe.crm.statuses.StatusColor;
import com.vibe.crm.statuses.StatusRepository;
import com.vibe.crm.tags.Tag;
import com.vibe.crm.tags.TagColor;
import com.vibe.crm.tags.TagRepository;
import com.vibe.crm.users.Role;
import com.vibe.crm.users.User;
import com.vibe.crm.users.UserRepository;

import jakarta.persistence.EntityManager;

/** Подготовка данных для интеграционных тестов. База общая на прогон — каждый тест чистит, что использует. */
@Component
public class TestData {

	private final UserRepository users;

	private final PasswordEncoder encoder;

	private final LeadRepository leads;

	private final LeadEventRepository events;

	private final StatusRepository statuses;

	private final TagRepository tags;

	private final EntityManager em;

	public TestData(UserRepository users, PasswordEncoder encoder, LeadRepository leads, LeadEventRepository events,
			StatusRepository statuses, TagRepository tags, EntityManager em) {
		this.users = users;
		this.encoder = encoder;
		this.leads = leads;
		this.events = events;
		this.statuses = statuses;
		this.tags = tags;
		this.em = em;
	}

	@Transactional
	public User user(String login, String password, Role role) {
		return users.findByLogin(login)
			.orElseGet(() -> users.save(new User(login, encoder.encode(password), login, role)));
	}

	@Transactional
	public Lead lead(long telegramUserId) {
		Status first = statuses.findFirstByOrderByPositionAsc().orElseThrow();
		return leads.save(new Lead(telegramUserId, telegramUserId, first, Instant.now()));
	}

	@Transactional
	public void clearLeads() {
		events.deleteAllInBatch();
		em.createNativeQuery("delete from lead_tags").executeUpdate();
		leads.deleteAllInBatch();
	}

	/** Пользователей нельзя удалить, пока на них ссылаются лиды и история. */
	@Transactional
	public void clearUsers() {
		clearLeads();
		users.deleteAllInBatch();
	}

	@Transactional
	public void resetStatusesAndTags() {
		clearLeads();
		statuses.deleteAllInBatch();
		tags.deleteAllInBatch();
		List<Object[]> seed = List.of(new Object[] { "Новый", StatusColor.BLUE }, new Object[] { "В работе", StatusColor.ORANGE },
				new Object[] { "Ждёт ответа", StatusColor.PURPLE }, new Object[] { "Квалифицирован", StatusColor.TEAL },
				new Object[] { "Сделка", StatusColor.GREEN }, new Object[] { "Отказ", StatusColor.GRAY });
		for (int i = 0; i < seed.size(); i++) {
			String name = (String) seed.get(i)[0];
			statuses.save(new Status(name, (StatusColor) seed.get(i)[1], i, name.equals("Сделка")));
		}
		for (Object[] t : List.of(new Object[] { "VIP", TagColor.BRICK }, new Object[] { "Опт", TagColor.BLUE },
				new Object[] { "Розница", TagColor.PURPLE }, new Object[] { "Повторный", TagColor.GREEN },
				new Object[] { "Срочно", TagColor.RED })) {
			tags.save(new Tag((String) t[0], (TagColor) t[1]));
		}
	}

}
```

- [ ] **Step 2: Падающий тест `StatusControllerTest`**

```java
package com.vibe.crm.statuses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.vibe.crm.IntegrationTest;
import com.vibe.crm.TestData;
import com.vibe.crm.TestLogin;
import com.vibe.crm.leads.Lead;
import com.vibe.crm.leads.LeadEvent;
import com.vibe.crm.leads.LeadEventRepository;
import com.vibe.crm.leads.LeadEventType;
import com.vibe.crm.leads.LeadRepository;
import com.vibe.crm.users.Role;

import jakarta.servlet.http.Cookie;

@IntegrationTest
class StatusControllerTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	StatusRepository statuses;

	@Autowired
	LeadRepository leads;

	@Autowired
	LeadEventRepository events;

	@Autowired
	TransactionTemplate tx;

	Cookie admin;

	Cookie manager;

	@BeforeEach
	void setUp() throws Exception {
		data.resetStatusesAndTags();
		data.user("st-admin", "admin-pass-1", Role.ADMIN);
		data.user("st-manager", "manager-pass", Role.MANAGER);
		admin = TestLogin.as(mvc, "st-admin", "admin-pass-1");
		manager = TestLogin.as(mvc, "st-manager", "manager-pass");
	}

	private String json(String s) {
		return s.replace('\'', '"');
	}

	private Long idOf(String name) {
		return statuses.findAllByOrderByPositionAsc().stream().filter(s -> s.getName().equals(name)).findFirst()
			.orElseThrow().getId();
	}

	@Test
	void listIsOrderedWithLeadCounts() throws Exception {
		data.lead(1);
		data.lead(2);

		mvc.perform(get("/api/statuses").cookie(manager))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(6))
			.andExpect(jsonPath("$[0].name").value("Новый"))
			.andExpect(jsonPath("$[0].color").value("BLUE"))
			.andExpect(jsonPath("$[0].leadCount").value(2))
			.andExpect(jsonPath("$[4].won").value(true))
			.andExpect(jsonPath("$[5].leadCount").value(0));
	}

	@Test
	void adminCreatesStatusAtTheEnd() throws Exception {
		mvc.perform(post("/api/statuses").cookie(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json("{'name':' Счёт выставлен ','color':'PINK'}")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("Счёт выставлен"))
			.andExpect(jsonPath("$.position").value(6))
			.andExpect(jsonPath("$.won").value(false));
	}

	@Test
	void managerCannotChangeStatuses() throws Exception {
		mvc.perform(post("/api/statuses").cookie(manager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json("{'name':'X','color':'PINK'}")))
			.andExpect(status().isForbidden());
		mvc.perform(delete("/api/statuses/{id}", idOf("Отказ")).cookie(manager).with(csrf()))
			.andExpect(status().isForbidden());
	}

	@Test
	void duplicateNameIsConflictIgnoringCase() throws Exception {
		mvc.perform(post("/api/statuses").cookie(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json("{'name':'новый','color':'PINK'}")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value("Статус с таким названием уже есть"));
	}

	@Test
	void renameAndRecolor() throws Exception {
		mvc.perform(patch("/api/statuses/{id}", idOf("Сделка")).cookie(admin).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(json("{'name':'Оплачено','color':'TEAL'}")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Оплачено"))
			.andExpect(jsonPath("$.won").value(true));
	}

	@Test
	void reorder() throws Exception {
		List<Long> ids = statuses.findAllByOrderByPositionAsc().stream().map(Status::getId).toList().reversed();

		mvc.perform(put("/api/statuses/order").cookie(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"ids\":" + ids + "}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].name").value("Отказ"))
			.andExpect(jsonPath("$[5].name").value("Новый"));

		mvc.perform(put("/api/statuses/order").cookie(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"ids\":" + ids.subList(0, 3) + "}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Передайте все статусы в новом порядке"));
	}

	@Test
	void deleteMovesLeadsToFirstStatusWithHistory() throws Exception {
		Lead lead = data.lead(10);
		Long work = idOf("В работе");
		tx.executeWithoutResult(t -> leads.findById(lead.getId()).orElseThrow().setStatus(statuses.getReferenceById(work)));

		mvc.perform(delete("/api/statuses/{id}", work).cookie(admin).with(csrf())).andExpect(status().isNoContent());

		tx.executeWithoutResult(t -> {
			Lead reloaded = leads.findById(lead.getId()).orElseThrow();
			assertThat(reloaded.getStatus().getName()).isEqualTo("Новый");
			List<LeadEvent> history = events.findByLeadOrderByCreatedAtDescIdDesc(reloaded);
			assertThat(history).hasSize(1);
			assertThat(history.getFirst().getType()).isEqualTo(LeadEventType.STATUS_CHANGED);
			assertThat(history.getFirst().getOldValue()).isEqualTo("В работе");
			assertThat(history.getFirst().getNewValue()).isEqualTo("Новый");
			assertThat(history.getFirst().getActor().getLogin()).isEqualTo("st-admin");
		});
		assertThat(statuses.findAllByOrderByPositionAsc()).extracting(Status::getPosition).containsExactly(0, 1, 2, 3, 4);
	}

	@Test
	void deletingFirstStatusMovesLeadsToNextOne() throws Exception {
		Lead lead = data.lead(11);

		mvc.perform(delete("/api/statuses/{id}", idOf("Новый")).cookie(admin).with(csrf()))
			.andExpect(status().isNoContent());

		tx.executeWithoutResult(t -> assertThat(leads.findById(lead.getId()).orElseThrow().getStatus().getName())
			.isEqualTo("В работе"));
	}

	@Test
	void cannotDeleteLastStatus() throws Exception {
		for (String name : List.of("Новый", "В работе", "Ждёт ответа", "Квалифицирован", "Сделка")) {
			mvc.perform(delete("/api/statuses/{id}", idOf(name)).cookie(admin).with(csrf()))
				.andExpect(status().isNoContent());
		}
		mvc.perform(delete("/api/statuses/{id}", idOf("Отказ")).cookie(admin).with(csrf()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value("Нельзя удалить единственный статус"));
	}

}
```

- [ ] **Step 3: Запустить — падает** (`./mvnw -q test -Dtest=StatusControllerTest`).

- [ ] **Step 4: Включить method security** — в `SecurityConfig` добавить аннотацию класса `@EnableMethodSecurity` (`org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity`).

- [ ] **Step 5: `statuses/StatusDto.java`**

```java
package com.vibe.crm.statuses;

public record StatusDto(Long id, String name, StatusColor color, int position, boolean won, long leadCount) {

	static StatusDto of(Status s, long leadCount) {
		return new StatusDto(s.getId(), s.getName(), s.getColor(), s.getPosition(), s.isWon(), leadCount);
	}

}
```

- [ ] **Step 6: `statuses/StatusService.java`**

```java
package com.vibe.crm.statuses;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.auth.CurrentUser;
import com.vibe.crm.common.ApiException;
import com.vibe.crm.leads.Lead;
import com.vibe.crm.leads.LeadEventType;
import com.vibe.crm.leads.LeadHistory;
import com.vibe.crm.leads.LeadRepository;

@Service
@Transactional
public class StatusService {

	private final StatusRepository statuses;

	private final LeadRepository leads;

	private final LeadHistory history;

	public StatusService(StatusRepository statuses, LeadRepository leads, LeadHistory history) {
		this.statuses = statuses;
		this.leads = leads;
		this.history = history;
	}

	@Transactional(readOnly = true)
	public List<StatusDto> list() {
		Map<Long, Long> counts = new HashMap<>();
		for (Object[] row : leads.countByStatus()) {
			counts.put((Long) row[0], (Long) row[1]);
		}
		return statuses.findAllByOrderByPositionAsc().stream()
			.map(s -> StatusDto.of(s, counts.getOrDefault(s.getId(), 0L)))
			.toList();
	}

	@Transactional(readOnly = true)
	public Status get(Long id) {
		return statuses.findById(id).orElseThrow(() -> ApiException.notFound("Статус не найден"));
	}

	/** Первый этап воронки — его получают новые лиды. */
	@Transactional(readOnly = true)
	public Status first() {
		return statuses.findFirstByOrderByPositionAsc()
			.orElseThrow(() -> new IllegalStateException("В системе нет ни одного статуса"));
	}

	public StatusDto create(String name, StatusColor color) {
		if (statuses.existsByNameIgnoreCase(name.trim())) {
			throw ApiException.conflict("Статус с таким названием уже есть");
		}
		int position = (int) statuses.count();
		return StatusDto.of(statuses.save(new Status(name, color, position)), 0);
	}

	public StatusDto update(Long id, String name, StatusColor color) {
		Status status = get(id);
		if (statuses.existsByNameIgnoreCaseAndIdNot(name.trim(), id)) {
			throw ApiException.conflict("Статус с таким названием уже есть");
		}
		status.setName(name);
		status.setColor(color);
		return StatusDto.of(status, leads.countByStatus(status));
	}

	public List<StatusDto> reorder(List<Long> ids) {
		List<Status> all = statuses.findAllByOrderByPositionAsc();
		if (ids.size() != all.size() || !new HashSet<>(ids).equals(new HashSet<>(all.stream().map(Status::getId).toList()))) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Передайте все статусы в новом порядке");
		}
		Map<Long, Status> byId = new HashMap<>();
		all.forEach(s -> byId.put(s.getId(), s));
		for (int i = 0; i < ids.size(); i++) {
			byId.get(ids.get(i)).setPosition(i);
		}
		statuses.flush();
		return list();
	}

	/** Лиды удаляемого статуса переходят в первый из оставшихся; позиции перенумеровываются. */
	public void delete(Long id) {
		Status status = get(id);
		List<Status> rest = statuses.findAllByOrderByPositionAsc().stream().filter(s -> !s.getId().equals(id)).toList();
		if (rest.isEmpty()) {
			throw ApiException.conflict("Нельзя удалить единственный статус");
		}
		Status fallback = rest.getFirst();
		Long actorId = CurrentUser.get().id();
		for (Lead lead : leads.findByStatus(status)) {
			lead.setStatus(fallback);
			history.record(lead, actorId, LeadEventType.STATUS_CHANGED, status.getName(), fallback.getName());
		}
		leads.flush();
		statuses.delete(status);
		for (int i = 0; i < rest.size(); i++) {
			rest.get(i).setPosition(i);
		}
	}

}
```

- [ ] **Step 7: `statuses/StatusController.java`**

```java
package com.vibe.crm.statuses;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/statuses")
public class StatusController {

	public record StatusRequest(
			@NotBlank(message = "Введите название") @Size(max = 64, message = "Название: до 64 символов") String name,
			@NotNull(message = "Выберите цвет") StatusColor color) {
	}

	public record OrderRequest(@NotEmpty(message = "Передайте все статусы в новом порядке") List<Long> ids) {
	}

	private final StatusService service;

	public StatusController(StatusService service) {
		this.service = service;
	}

	@GetMapping
	public List<StatusDto> list() {
		return service.list();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasRole('ADMIN')")
	public StatusDto create(@Valid @RequestBody StatusRequest body) {
		return service.create(body.name(), body.color());
	}

	@PatchMapping("/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public StatusDto update(@PathVariable Long id, @Valid @RequestBody StatusRequest body) {
		return service.update(id, body.name(), body.color());
	}

	@PutMapping("/order")
	@PreAuthorize("hasRole('ADMIN')")
	public List<StatusDto> reorder(@Valid @RequestBody OrderRequest body) {
		return service.reorder(body.ids());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasRole('ADMIN')")
	public void delete(@PathVariable Long id) {
		service.delete(id);
	}

}
```

- [ ] **Step 8: Запустить тест** → `Tests run: 9, Failures: 0`. Затем `./mvnw -q verify` (тест `LeadStorageTest.seedStatusesAndTagsExist` зависит от сида — если падает из-за порядка тестов, в его `@BeforeEach` вызвать `data.resetStatusesAndTags()` вместо `leads.deleteAll()`).

- [ ] **Step 9: Commit** — «Добавить настройку статусов воронки».

---

### Task 3: API тегов

**Files:**
- Create: `backend/src/main/java/com/vibe/crm/tags/{TagDto,TagService,TagController}.java`
- Test: `backend/src/test/java/com/vibe/crm/tags/TagControllerTest.java`

**Interfaces:**
- Consumes: Task 1, `TestData`, `LeadHistory`, `CurrentUser`.
- Produces:
  - `record TagDto(Long id, String name, TagColor color, long leadCount)`.
  - `TagService`: `List<TagDto> list()`, `TagDto create(String name, TagColor color)`, `TagDto update(Long id, String name, TagColor color)`, `void delete(Long id)`, `Tag get(Long id)`.
  - HTTP: `GET /api/tags`; `POST /api/tags {name,color}` → 201 (ADMIN); `PATCH /api/tags/{id}` (ADMIN); `DELETE /api/tags/{id}` → 204 (ADMIN). Дубликат имени → 409 «Тег с таким названием уже есть». Удаление снимает тег со всех лидов с событием `TAG_REMOVED`.

- [ ] **Step 1: Падающий тест `TagControllerTest`**

```java
package com.vibe.crm.tags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.vibe.crm.IntegrationTest;
import com.vibe.crm.TestData;
import com.vibe.crm.TestLogin;
import com.vibe.crm.leads.Lead;
import com.vibe.crm.leads.LeadEventRepository;
import com.vibe.crm.leads.LeadEventType;
import com.vibe.crm.leads.LeadRepository;
import com.vibe.crm.users.Role;

import jakarta.servlet.http.Cookie;

@IntegrationTest
class TagControllerTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	TagRepository tags;

	@Autowired
	LeadRepository leads;

	@Autowired
	LeadEventRepository events;

	@Autowired
	TransactionTemplate tx;

	Cookie admin;

	Cookie manager;

	@BeforeEach
	void setUp() throws Exception {
		data.resetStatusesAndTags();
		data.user("tg-admin", "admin-pass-1", Role.ADMIN);
		data.user("tg-manager", "manager-pass", Role.MANAGER);
		admin = TestLogin.as(mvc, "tg-admin", "admin-pass-1");
		manager = TestLogin.as(mvc, "tg-manager", "manager-pass");
	}

	private String json(String s) {
		return s.replace('\'', '"');
	}

	private Tag tag(String name) {
		return tags.findAllByOrderByIdAsc().stream().filter(t -> t.getName().equals(name)).findFirst().orElseThrow();
	}

	private void addTag(Lead lead, String name) {
		tx.executeWithoutResult(t -> leads.findById(lead.getId()).orElseThrow().getTags().add(tag(name)));
	}

	@Test
	void listWithLeadCounts() throws Exception {
		addTag(data.lead(1), "Опт");
		addTag(data.lead(2), "Опт");

		mvc.perform(get("/api/tags").cookie(manager))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(5))
			.andExpect(jsonPath("$[0].name").value("VIP"))
			.andExpect(jsonPath("$[0].color").value("BRICK"))
			.andExpect(jsonPath("$[0].leadCount").value(0))
			.andExpect(jsonPath("$[1].leadCount").value(2));
	}

	@Test
	void createRenameAndDuplicate() throws Exception {
		mvc.perform(post("/api/tags").cookie(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json("{'name':'Холодный','color':'TEAL'}")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("Холодный"));

		mvc.perform(patch("/api/tags/{id}", tag("Холодный").getId()).cookie(admin).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(json("{'name':'Тёплый','color':'MUSTARD'}")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.color").value("MUSTARD"));

		mvc.perform(post("/api/tags").cookie(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json("{'name':'vip','color':'TEAL'}")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value("Тег с таким названием уже есть"));
	}

	@Test
	void managerCannotChangeTags() throws Exception {
		mvc.perform(delete("/api/tags/{id}", tag("VIP").getId()).cookie(manager).with(csrf()))
			.andExpect(status().isForbidden());
	}

	@Test
	void deleteRemovesTagFromLeadsWithHistoryButKeepsLeads() throws Exception {
		Lead lead = data.lead(5);
		addTag(lead, "Срочно");
		addTag(lead, "VIP");

		mvc.perform(delete("/api/tags/{id}", tag("Срочно").getId()).cookie(admin).with(csrf()))
			.andExpect(status().isNoContent());

		tx.executeWithoutResult(t -> {
			Lead reloaded = leads.findById(lead.getId()).orElseThrow();
			assertThat(reloaded.getTags()).extracting(Tag::getName).containsExactly("VIP");
			assertThat(events.findByLeadOrderByCreatedAtDescIdDesc(reloaded)).singleElement().satisfies(e -> {
				assertThat(e.getType()).isEqualTo(LeadEventType.TAG_REMOVED);
				assertThat(e.getOldValue()).isEqualTo("Срочно");
			});
		});
	}

}
```

- [ ] **Step 2: Запустить — падает.**

- [ ] **Step 3: `tags/TagDto.java`**

```java
package com.vibe.crm.tags;

public record TagDto(Long id, String name, TagColor color, long leadCount) {

	static TagDto of(Tag t, long leadCount) {
		return new TagDto(t.getId(), t.getName(), t.getColor(), leadCount);
	}

}
```

- [ ] **Step 4: `tags/TagService.java`**

```java
package com.vibe.crm.tags;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.auth.CurrentUser;
import com.vibe.crm.common.ApiException;
import com.vibe.crm.leads.Lead;
import com.vibe.crm.leads.LeadEventType;
import com.vibe.crm.leads.LeadHistory;
import com.vibe.crm.leads.LeadRepository;

@Service
@Transactional
public class TagService {

	private final TagRepository tags;

	private final LeadRepository leads;

	private final LeadHistory history;

	public TagService(TagRepository tags, LeadRepository leads, LeadHistory history) {
		this.tags = tags;
		this.leads = leads;
		this.history = history;
	}

	@Transactional(readOnly = true)
	public List<TagDto> list() {
		Map<Long, Long> counts = counts();
		return tags.findAllByOrderByIdAsc().stream().map(t -> TagDto.of(t, counts.getOrDefault(t.getId(), 0L))).toList();
	}

	@Transactional(readOnly = true)
	public Tag get(Long id) {
		return tags.findById(id).orElseThrow(() -> ApiException.notFound("Тег не найден"));
	}

	public TagDto create(String name, TagColor color) {
		if (tags.existsByNameIgnoreCase(name.trim())) {
			throw ApiException.conflict("Тег с таким названием уже есть");
		}
		return TagDto.of(tags.save(new Tag(name, color)), 0);
	}

	public TagDto update(Long id, String name, TagColor color) {
		Tag tag = get(id);
		if (tags.existsByNameIgnoreCaseAndIdNot(name.trim(), id)) {
			throw ApiException.conflict("Тег с таким названием уже есть");
		}
		tag.setName(name);
		tag.setColor(color);
		return TagDto.of(tag, counts().getOrDefault(id, 0L));
	}

	/** Тег снимается со всех лидов (с записью в историю), сами лиды остаются. */
	public void delete(Long id) {
		Tag tag = get(id);
		Long actorId = CurrentUser.get().id();
		for (Lead lead : leads.findByTagsContains(tag)) {
			lead.getTags().remove(tag);
			history.record(lead, actorId, LeadEventType.TAG_REMOVED, tag.getName(), null);
		}
		leads.flush();
		tags.delete(tag);
	}

	private Map<Long, Long> counts() {
		Map<Long, Long> counts = new HashMap<>();
		for (Object[] row : leads.countByTag()) {
			counts.put((Long) row[0], (Long) row[1]);
		}
		return counts;
	}

}
```

- [ ] **Step 5: `tags/TagController.java`**

```java
package com.vibe.crm.tags;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/tags")
public class TagController {

	public record TagRequest(
			@NotBlank(message = "Введите название") @Size(max = 64, message = "Название: до 64 символов") String name,
			@NotNull(message = "Выберите цвет") TagColor color) {
	}

	private final TagService service;

	public TagController(TagService service) {
		this.service = service;
	}

	@GetMapping
	public List<TagDto> list() {
		return service.list();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasRole('ADMIN')")
	public TagDto create(@Valid @RequestBody TagRequest body) {
		return service.create(body.name(), body.color());
	}

	@PatchMapping("/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public TagDto update(@PathVariable Long id, @Valid @RequestBody TagRequest body) {
		return service.update(id, body.name(), body.color());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasRole('ADMIN')")
	public void delete(@PathVariable Long id) {
		service.delete(id);
	}

}
```

- [ ] **Step 6: Тест → `Tests run: 4, Failures: 0`; `./mvnw -q verify` зелёный.**

- [ ] **Step 7: Commit** — «Добавить настройку тегов»; push в `dev` по скиллу `github-push`.
