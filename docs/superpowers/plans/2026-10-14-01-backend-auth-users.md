# Этап 1. Бекенд: вход в систему и пользователи — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Вход по логину и паролю (сессия в cookie, хранится в Postgres), первый администратор из `.env`, админ-API управления пользователями, список менеджеров.

**Architecture:** Spring Security 7 с JSON-эндпоинтами входа/выхода в собственном контроллере (`SecurityContextRepository` вручную), CSRF в режиме SPA, Spring Session JDBC. Тесты — `@SpringBootTest` + MockMvc на Testcontainers PostgreSQL.

**Tech Stack:** Spring Boot 4.1.1, Spring Security 7, Spring Session JDBC, Liquibase, Testcontainers, JUnit 5, AssertJ.

Спека: `docs/superpowers/specs/2026-10-14-telegram-leads-crm-design.md`.

## Global Constraints

- Java 25, Spring Boot 4.1.1 (`backend/pom.xml`), пакет `com.vibe.crm`.
- Boot 4: стартеры модульные, у каждого свой `*-test`; `@AutoConfigureMockMvc` — `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`; Jackson 3 (`tools.jackson.databind.*`), аннотации — `com.fasterxml.jackson.annotation.*`.
- Схема БД — только Liquibase-миграциями `backend/src/main/resources/db/changelog/changes/NNN-*.yaml`, `author: crm`, id = имя файла; `ddl-auto: validate` (скилл `.claude/skills/db-migrations`).
- Все эндпоинты под `/api`. Ошибки — JSON `{"message": "...", "fields": {...}}`, тексты на русском.
- Роли: `ADMIN`, `MANAGER`. Пароли — BCrypt.
- Коммиты — на русском, без упоминаний ИИ (скилл `.claude/skills/github-push`), в ветку `dev`.

---

### Task 1: Тесты на Testcontainers

Сейчас тест подключается к рабочей БД из docker compose. Переводим тесты на одноразовый контейнер PostgreSQL 18.

**Files:**
- Modify: `backend/pom.xml` (dependencies)
- Create: `backend/src/test/java/com/vibe/crm/TestcontainersConfiguration.java`
- Create: `backend/src/test/java/com/vibe/crm/IntegrationTest.java`
- Modify: `backend/src/test/java/com/vibe/crm/CrmApplicationTests.java`
- Modify: `CLAUDE.md` (раздел «Команды»)

**Interfaces:**
- Produces: аннотация `@IntegrationTest` — `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfiguration.class)`; все интеграционные тесты дальше помечаются ею.

- [ ] **Step 1: Добавить зависимости в `backend/pom.xml`** (внутрь `<dependencies>`, после `spring-boot-starter-webmvc-test`):

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-testcontainers</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-junit-jupiter</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-postgresql</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 2: Создать `TestcontainersConfiguration.java`**

```java
package com.vibe.crm;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
	}

}
```

- [ ] **Step 3: Создать `IntegrationTest.java`**

```java
package com.vibe.crm;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Интеграционный тест: полный контекст приложения, MockMvc и одноразовый PostgreSQL в контейнере.
 * Контейнер и контекст переиспользуются между тестовыми классами с одинаковой конфигурацией.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {

}
```

- [ ] **Step 4: Перевести `CrmApplicationTests` на `@IntegrationTest`**

```java
package com.vibe.crm;

import org.junit.jupiter.api.Test;

@IntegrationTest
class CrmApplicationTests {

	@Test
	void contextLoads() {
	}

}
```

- [ ] **Step 5: Проверить, что тест проходит без рабочей БД**

Run: `docker compose stop postgres && (cd backend && ./mvnw -q verify); docker compose start postgres`
Expected: сборка успешна, `Tests run: 1, Failures: 0, Errors: 0` в `backend/target/surefire-reports/com.vibe.crm.CrmApplicationTests.txt`.

- [ ] **Step 6: Обновить `CLAUDE.md`** — заменить абзац «Тесты `@SpringBootTest` подключаются к реальной БД…» на:

```markdown
Интеграционные тесты помечаются `@IntegrationTest` (`backend/src/test/java/com/vibe/crm/IntegrationTest.java`): полный контекст + MockMvc + одноразовый PostgreSQL через Testcontainers. Нужен запущенный Docker; рабочая БД из docker compose тестами не используется.
```

- [ ] **Step 7: Commit**

```bash
git add backend/pom.xml backend/src/test CLAUDE.md
git commit -m "Перевести интеграционные тесты на Testcontainers"
```

---

### Task 2: Таблица и сущность пользователей

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/002-create-users.yaml`
- Create: `backend/src/main/java/com/vibe/crm/users/Role.java`
- Create: `backend/src/main/java/com/vibe/crm/users/User.java`
- Create: `backend/src/main/java/com/vibe/crm/users/UserRepository.java`
- Test: `backend/src/test/java/com/vibe/crm/users/UserRepositoryTest.java`

**Interfaces:**
- Produces:
  - `enum Role { ADMIN, MANAGER }`
  - `User` (JPA, таблица `users`): `Long getId()`, `String getLogin()`, `String getPasswordHash()`, `String getDisplayName()`, `Role getRole()`, `boolean isActive()`, `Instant getCreatedAt()`; конструктор `User(String login, String passwordHash, String displayName, Role role)` (логин приводится к нижнему регистру и обрезается); сеттеры `setPasswordHash`, `setDisplayName`, `setRole`, `setActive`.
  - `UserRepository extends JpaRepository<User, Long>`: `Optional<User> findByLogin(String login)`, `boolean existsByLogin(String login)`, `long countByRoleAndActiveTrue(Role role)`, `List<User> findAllByOrderByDisplayNameAsc()`, `List<User> findByActiveTrueOrderByDisplayNameAsc()`.

- [ ] **Step 1: Написать падающий тест `UserRepositoryTest`**

```java
package com.vibe.crm.users;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.IntegrationTest;

@IntegrationTest
@Transactional
class UserRepositoryTest {

	@Autowired
	UserRepository users;

	@Test
	void loginIsStoredLowercaseAndFoundByIt() {
		users.save(new User("  Maria.K ", "hash", "Мария К.", Role.MANAGER));

		assertThat(users.findByLogin("maria.k")).get().satisfies(u -> {
			assertThat(u.getDisplayName()).isEqualTo("Мария К.");
			assertThat(u.getRole()).isEqualTo(Role.MANAGER);
			assertThat(u.isActive()).isTrue();
			assertThat(u.getCreatedAt()).isNotNull();
		});
		assertThat(users.existsByLogin("maria.k")).isTrue();
	}

	@Test
	void countsOnlyActiveUsersOfRole() {
		users.save(new User("admin1", "h", "Админ 1", Role.ADMIN));
		User blocked = new User("admin2", "h", "Админ 2", Role.ADMIN);
		blocked.setActive(false);
		users.save(blocked);
		users.save(new User("mgr", "h", "Менеджер", Role.MANAGER));

		assertThat(users.countByRoleAndActiveTrue(Role.ADMIN)).isEqualTo(1);
	}

}
```

- [ ] **Step 2: Запустить и убедиться, что падает**

Run: `cd backend && ./mvnw -q test -Dtest=UserRepositoryTest`
Expected: ошибка компиляции — нет `User`, `Role`, `UserRepository`.

- [ ] **Step 3: Создать миграцию `002-create-users.yaml`**

```yaml
databaseChangeLog:
  - changeSet:
      id: 002-create-users
      author: crm
      changes:
        - createTable:
            tableName: users
            columns:
              - column:
                  name: id
                  type: bigint
                  autoIncrement: true
                  constraints:
                    primaryKey: true
                    primaryKeyName: pk_users
              - column:
                  name: login
                  type: varchar(64)
                  constraints:
                    nullable: false
                    unique: true
                    uniqueConstraintName: uq_users_login
              - column:
                  name: password_hash
                  type: varchar(100)
                  constraints:
                    nullable: false
              - column:
                  name: display_name
                  type: varchar(100)
                  constraints:
                    nullable: false
              - column:
                  name: role
                  type: varchar(16)
                  constraints:
                    nullable: false
              - column:
                  name: active
                  type: boolean
                  defaultValueBoolean: true
                  constraints:
                    nullable: false
              - column:
                  name: created_at
                  type: timestamp with time zone
                  defaultValueComputed: now()
                  constraints:
                    nullable: false
```

- [ ] **Step 4: Создать `Role.java`**

```java
package com.vibe.crm.users;

public enum Role {
	ADMIN, MANAGER
}
```

- [ ] **Step 5: Создать `User.java`**

```java
package com.vibe.crm.users;

import java.time.Instant;
import java.util.Locale;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 64, unique = true)
	private String login;

	@Column(name = "password_hash", nullable = false, length = 100)
	private String passwordHash;

	@Column(name = "display_name", nullable = false, length = 100)
	private String displayName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private Role role;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	protected User() {
	}

	public User(String login, String passwordHash, String displayName, Role role) {
		this.login = normalizeLogin(login);
		this.passwordHash = passwordHash;
		this.displayName = displayName.trim();
		this.role = role;
	}

	public static String normalizeLogin(String login) {
		return login.trim().toLowerCase(Locale.ROOT);
	}

	public Long getId() {
		return id;
	}

	public String getLogin() {
		return login;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName.trim();
	}

	public Role getRole() {
		return role;
	}

	public void setRole(Role role) {
		this.role = role;
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
```

- [ ] **Step 6: Создать `UserRepository.java`**

```java
package com.vibe.crm.users;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByLogin(String login);

	boolean existsByLogin(String login);

	long countByRoleAndActiveTrue(Role role);

	List<User> findAllByOrderByDisplayNameAsc();

	List<User> findByActiveTrueOrderByDisplayNameAsc();

}
```

- [ ] **Step 7: Запустить тест**

Run: `cd backend && ./mvnw -q test -Dtest=UserRepositoryTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Step 8: Commit**

```bash
git add backend/src
git commit -m "Добавить таблицу и сущность пользователей"
```

---

### Task 3: Вход, выход, текущий пользователь

**Files:**
- Modify: `backend/pom.xml` — `spring-boot-starter-security`, `spring-boot-starter-session-jdbc` (+ `-test` для обоих)
- Create: `backend/src/main/resources/db/changelog/changes/003-create-spring-session.yaml`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/vibe/crm/common/ApiError.java`
- Create: `backend/src/main/java/com/vibe/crm/common/ApiException.java`
- Create: `backend/src/main/java/com/vibe/crm/common/ApiExceptionHandler.java`
- Create: `backend/src/main/java/com/vibe/crm/auth/CrmUserPrincipal.java`
- Create: `backend/src/main/java/com/vibe/crm/auth/CrmUserDetailsService.java`
- Create: `backend/src/main/java/com/vibe/crm/auth/SecurityConfig.java`
- Create: `backend/src/main/java/com/vibe/crm/auth/AuthController.java`
- Create: `backend/src/main/java/com/vibe/crm/auth/CurrentUser.java`
- Create: `backend/src/test/java/com/vibe/crm/TestLogin.java` (хелпер входа для тестов)
- Test: `backend/src/test/java/com/vibe/crm/auth/AuthControllerTest.java`

**Interfaces:**
- Consumes: `User`, `UserRepository`, `Role` (Task 2).
- Produces:
  - `ApiException(HttpStatus status, String message)` и `ApiException(HttpStatus status, String message, Map<String,String> fields)` — бросается из сервисов, превращается в `ApiError`.
  - `record ApiError(String message, Map<String,String> fields)`.
  - `CrmUserPrincipal implements UserDetails`: `Long id()`, `String displayName()`, `Role role()`; authority `ROLE_ADMIN`/`ROLE_MANAGER`.
  - `CurrentUser`: `static CrmUserPrincipal get()` — текущий пользователь из `SecurityContextHolder`.
  - `record MeResponse(Long id, String login, String displayName, Role role)` в `AuthController`.
  - HTTP: `POST /api/auth/login {login, password}` → 200 `MeResponse` | 401; `POST /api/auth/logout` → 204; `GET /api/auth/me` → 200 `MeResponse` | 401. Доступ без входа: `/api/auth/login`, `/api/ping`, `/actuator/health`. Пути `/api/admin/**` — только `ADMIN`. Остальное `/api/**` — любой вошедший.
  - Для тестов: в MockMvc POST-запросы требуют `.with(csrf())`.

- [ ] **Step 1: Добавить зависимости в `backend/pom.xml`**

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-session-jdbc</artifactId>
		</dependency>
```

и в тестовые:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-session-jdbc-test</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 2: Сверить схему Spring Session для PostgreSQL**

Run: `cd backend && ./mvnw -q dependency:resolve && unzip -p $(find ~/.m2/repository/org/springframework/session/spring-session-jdbc -name 'spring-session-jdbc-*.jar' ! -name '*sources*' | sort | tail -1) org/springframework/session/jdbc/schema-postgresql.sql`
Expected: `CREATE TABLE SPRING_SESSION (...)` и `SPRING_SESSION_ATTRIBUTES (...)`. Если колонки отличаются от Step 3 — использовать текст из jar.

- [ ] **Step 3: Создать миграцию `003-create-spring-session.yaml`** (схема из jar; `sql` здесь оправдан — это готовый скрипт Spring Session):

```yaml
databaseChangeLog:
  - changeSet:
      id: 003-create-spring-session
      author: crm
      changes:
        - sql:
            sql: |
              CREATE TABLE SPRING_SESSION (
                PRIMARY_ID CHAR(36) NOT NULL,
                SESSION_ID CHAR(36) NOT NULL,
                CREATION_TIME BIGINT NOT NULL,
                LAST_ACCESS_TIME BIGINT NOT NULL,
                MAX_INACTIVE_INTERVAL INT NOT NULL,
                EXPIRY_TIME BIGINT NOT NULL,
                PRINCIPAL_NAME VARCHAR(100),
                CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
              );
              CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
              CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
              CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);
              CREATE TABLE SPRING_SESSION_ATTRIBUTES (
                SESSION_PRIMARY_ID CHAR(36) NOT NULL,
                ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
                ATTRIBUTE_BYTES BYTEA NOT NULL,
                CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
                CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
              );
      rollback:
        - sql:
            sql: DROP TABLE SPRING_SESSION_ATTRIBUTES; DROP TABLE SPRING_SESSION;
```

- [ ] **Step 4: Дополнить `application.yml`** — в блок `spring:` добавить (и импорт `.env`, чтобы `CRM_*`/`TELEGRAM_*` подхватывались при `./mvnw spring-boot:run` из `backend/`):

```yaml
  config:
    import:
      - optional:file:.env[.properties]
      - optional:file:../.env[.properties]
  session:
    jdbc:
      initialize-schema: never
    timeout: 30d
```

и в корень файла:

```yaml
server:
  port: ${SERVER_PORT:8080}
  servlet:
    session:
      cookie:
        http-only: true
        same-site: lax
```

(блок `server:` уже есть — дописать в него `servlet`, не дублируя `server:`).

- [ ] **Step 5: Создать `common/ApiError.java`, `common/ApiException.java`, `common/ApiExceptionHandler.java`**

```java
package com.vibe.crm.common;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(String message, Map<String, String> fields) {

	public ApiError(String message) {
		this(message, Map.of());
	}

}
```

```java
package com.vibe.crm.common;

import java.util.Map;

import org.springframework.http.HttpStatus;

/** Ошибка, которую нужно показать пользователю как есть. */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final Map<String, String> fields;

	public ApiException(HttpStatus status, String message) {
		this(status, message, Map.of());
	}

	public ApiException(HttpStatus status, String message, Map<String, String> fields) {
		super(message);
		this.status = status;
		this.fields = fields;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public Map<String, String> getFields() {
		return fields;
	}

	public static ApiException notFound(String message) {
		return new ApiException(HttpStatus.NOT_FOUND, message);
	}

	public static ApiException badRequest(String field, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, message, Map.of(field, message));
	}

	public static ApiException conflict(String message) {
		return new ApiException(HttpStatus.CONFLICT, message);
	}

}
```

```java
package com.vibe.crm.common;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ApiError> handleApi(ApiException e) {
		return ResponseEntity.status(e.getStatus()).body(new ApiError(e.getMessage(), e.getFields()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
		Map<String, String> fields = new LinkedHashMap<>();
		e.getBindingResult().getFieldErrors().forEach(fe -> fields.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
		return ResponseEntity.badRequest().body(new ApiError("Проверьте заполнение полей", fields));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError("Некорректный запрос"));
	}

}
```

- [ ] **Step 6a: Создать тестовый хелпер `TestLogin.java`**

```java
package com.vibe.crm;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;

public final class TestLogin {

	private TestLogin() {
	}

	/**
	 * Входит под пользователем и возвращает cookie сессии. Сессии хранит Spring Session (cookie SESSION),
	 * поэтому MockHttpSession здесь не работает — запросы должны нести эту cookie.
	 */
	public static Cookie as(MockMvc mvc, String login, String password) throws Exception {
		Cookie session = mvc.perform(post("/api/auth/login").with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"login\":\"" + login + "\",\"password\":\"" + password + "\"}"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getCookie("SESSION");
		if (session == null) {
			throw new AssertionError("После входа нет cookie SESSION");
		}
		return session;
	}

}
```

- [ ] **Step 6: Написать падающий тест `AuthControllerTest`**

```java
package com.vibe.crm.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.vibe.crm.IntegrationTest;
import com.vibe.crm.TestLogin;
import com.vibe.crm.users.Role;
import com.vibe.crm.users.User;
import com.vibe.crm.users.UserRepository;

import jakarta.servlet.http.Cookie;

@IntegrationTest
class AuthControllerTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder encoder;

	@BeforeEach
	void setUp() {
		users.deleteAll();
		users.save(new User("maria", encoder.encode("secret123"), "Мария К.", Role.MANAGER));
		User blocked = new User("oleg", encoder.encode("secret123"), "Олег Д.", Role.MANAGER);
		blocked.setActive(false);
		users.save(blocked);
	}

	@Test
	void meWithoutLoginIs401() throws Exception {
		mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void loginReturnsUserAndSessionCookieWorks() throws Exception {
		mvc.perform(post("/api/auth/login").with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"login\":\"Maria\",\"password\":\"secret123\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.displayName").value("Мария К."))
			.andExpect(jsonPath("$.role").value("MANAGER"))
			.andExpect(cookie().exists("SESSION"))
			.andExpect(cookie().httpOnly("SESSION", true));

		Cookie session = TestLogin.as(mvc, "maria", "secret123");
		mvc.perform(get("/api/auth/me").cookie(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.login").value("maria"));
	}

	@Test
	void wrongPasswordIs401WithMessage() throws Exception {
		mvc.perform(post("/api/auth/login").with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"login\":\"maria\",\"password\":\"nope\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.message").value("Неверный логин или пароль"));
	}

	@Test
	void blockedUserCannotLogin() throws Exception {
		mvc.perform(post("/api/auth/login").with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"login\":\"oleg\",\"password\":\"secret123\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.message").value("Учётная запись заблокирована"));
	}

	@Test
	void loginWithoutCsrfIsForbidden() throws Exception {
		mvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"login\":\"maria\",\"password\":\"secret123\"}"))
			.andExpect(status().isForbidden());
	}

	@Test
	void csrfCookieIsIssuedForSpa() throws Exception {
		mvc.perform(get("/api/auth/me")).andExpect(cookie().exists("XSRF-TOKEN"));
	}

	@Test
	void logoutEndsSession() throws Exception {
		Cookie session = TestLogin.as(mvc, "maria", "secret123");

		mvc.perform(post("/api/auth/logout").cookie(session).with(csrf())).andExpect(status().isNoContent());
		mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isUnauthorized());
	}

	@Test
	void adminPathsForbiddenForManager() throws Exception {
		Cookie session = TestLogin.as(mvc, "maria", "secret123");

		mvc.perform(get("/api/admin/users").cookie(session)).andExpect(status().isForbidden());
	}

}
```

- [ ] **Step 7: Запустить и убедиться, что падает**

Run: `cd backend && ./mvnw -q test -Dtest=AuthControllerTest`
Expected: ошибка компиляции (нет `PasswordEncoder`-бина/контроллера) или падения тестов.

- [ ] **Step 8: Создать `auth/CrmUserPrincipal.java`**

```java
package com.vibe.crm.auth;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.vibe.crm.users.Role;
import com.vibe.crm.users.User;

/** Вошедший пользователь. Хранится в сессии, поэтому содержит только неизменяемые простые поля. */
public record CrmUserPrincipal(Long id, String login, String passwordHash, String displayName, Role role,
		boolean active) implements UserDetails {

	public static CrmUserPrincipal of(User user) {
		return new CrmUserPrincipal(user.getId(), user.getLogin(), user.getPasswordHash(), user.getDisplayName(),
				user.getRole(), user.isActive());
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	@Override
	public String getPassword() {
		return passwordHash;
	}

	@Override
	public String getUsername() {
		return login;
	}

	@Override
	public boolean isEnabled() {
		return active;
	}

	public boolean isAdmin() {
		return role == Role.ADMIN;
	}

	/** Копия без хеша пароля — именно она кладётся в сессию. */
	public CrmUserPrincipal withoutPassword() {
		return new CrmUserPrincipal(id, login, null, displayName, role, active);
	}

}
```

- [ ] **Step 9: Создать `auth/CrmUserDetailsService.java`**

```java
package com.vibe.crm.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.vibe.crm.users.User;
import com.vibe.crm.users.UserRepository;

@Service
public class CrmUserDetailsService implements UserDetailsService {

	private final UserRepository users;

	public CrmUserDetailsService(UserRepository users) {
		this.users = users;
	}

	@Override
	public UserDetails loadUserByUsername(String login) {
		return users.findByLogin(User.normalizeLogin(login))
			.map(CrmUserPrincipal::of)
			.orElseThrow(() -> new UsernameNotFoundException(login));
	}

}
```

- [ ] **Step 10: Создать `auth/SecurityConfig.java`**

```java
package com.vibe.crm.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/api/auth/login", "/api/ping", "/actuator/health").permitAll()
				.requestMatchers("/api/admin/**").hasRole("ADMIN")
				.anyRequest().authenticated())
			.csrf(csrf -> csrf.spa())
			.exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
			.securityContext(sc -> sc.securityContextRepository(securityContextRepository()))
			.formLogin(f -> f.disable())
			.httpBasic(b -> b.disable())
			.logout(l -> l.disable());
		return http.build();
	}

	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	AuthenticationManager authenticationManager(CrmUserDetailsService userDetailsService, PasswordEncoder encoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
		provider.setPasswordEncoder(encoder);
		return new ProviderManager(provider);
	}

}
```

- [ ] **Step 11: Создать `auth/CurrentUser.java`**

```java
package com.vibe.crm.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {

	private CurrentUser() {
	}

	public static CrmUserPrincipal get() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !(auth.getPrincipal() instanceof CrmUserPrincipal principal)) {
			throw new IllegalStateException("Нет вошедшего пользователя");
		}
		return principal;
	}

}
```

- [ ] **Step 12: Создать `auth/AuthController.java`**

```java
package com.vibe.crm.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.vibe.crm.common.ApiException;
import com.vibe.crm.users.Role;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	public record LoginRequest(@NotBlank(message = "Введите логин") String login,
			@NotBlank(message = "Введите пароль") String password) {
	}

	public record MeResponse(Long id, String login, String displayName, Role role) {

		static MeResponse of(CrmUserPrincipal p) {
			return new MeResponse(p.id(), p.login(), p.displayName(), p.role());
		}

	}

	private final AuthenticationManager authenticationManager;

	private final SecurityContextRepository securityContextRepository;

	private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

	public AuthController(AuthenticationManager authenticationManager,
			SecurityContextRepository securityContextRepository) {
		this.authenticationManager = authenticationManager;
		this.securityContextRepository = securityContextRepository;
	}

	@PostMapping("/login")
	public MeResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		Authentication auth;
		try {
			auth = authenticationManager
				.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(body.login(), body.password()));
		}
		catch (DisabledException e) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "Учётная запись заблокирована");
		}
		catch (BadCredentialsException e) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "Неверный логин или пароль");
		}
		catch (AuthenticationException e) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "Неверный логин или пароль");
		}
		// Защита от фиксации сессии: новый id сессии после входа.
		request.getSession(true);
		request.changeSessionId();
		CrmUserPrincipal principal = ((CrmUserPrincipal) auth.getPrincipal()).withoutPassword();
		SecurityContext context = holder.createEmptyContext();
		context.setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
		holder.setContext(context);
		securityContextRepository.saveContext(context, request, response);
		return MeResponse.of(principal);
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		holder.clearContext();
	}

	@GetMapping("/me")
	public MeResponse me() {
		return MeResponse.of(CurrentUser.get());
	}

}
```

- [ ] **Step 13: Запустить тесты**

Run: `cd backend && ./mvnw -q test -Dtest=AuthControllerTest`
Expected: `Tests run: 8, Failures: 0, Errors: 0`.
Если `csrfCookieIsIssuedForSpa` падает (cookie не выдаётся на GET, потому что токен загружается лениво) — добавить в `SecurityConfig` фильтр, который принудительно читает токен:

```java
// в securityFilterChain, перед return:
http.addFilterAfter(new CsrfCookieFilter(), org.springframework.security.web.authentication.www.BasicAuthenticationFilter.class);
```

```java
package com.vibe.crm.auth;

import java.io.IOException;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Заставляет выдать cookie XSRF-TOKEN на любой запрос, чтобы SPA всегда имела токен. */
class CsrfCookieFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
		if (token != null) {
			token.getToken();
		}
		chain.doFilter(request, response);
	}

}
```

и перезапустить тест.

- [ ] **Step 14: Прогнать все тесты**

Run: `cd backend && ./mvnw -q verify`
Expected: все тесты зелёные.

- [ ] **Step 15: Commit**

```bash
git add backend
git commit -m "Добавить вход по логину и паролю с сессией в базе"
```

---

### Task 4: Первый администратор из `.env`

**Files:**
- Create: `backend/src/main/java/com/vibe/crm/users/AdminBootstrap.java`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/resources/application-test.yml` — не нужен; тестовые значения задаются в `@IntegrationTest` (см. Step 1)
- Modify: `backend/src/test/java/com/vibe/crm/IntegrationTest.java`
- Modify: `.env.example`
- Test: `backend/src/test/java/com/vibe/crm/users/AdminBootstrapTest.java`

**Interfaces:**
- Consumes: `UserRepository`, `User`, `Role`, `PasswordEncoder`.
- Produces: свойства `crm.admin.login`, `crm.admin.password`, `crm.admin.display-name` (по умолчанию «Администратор»); `AdminBootstrap.ensureAdmin()` — создаёт администратора, если пользователей нет; бросает `IllegalStateException`, если пользователей нет и логин/пароль пусты.

- [ ] **Step 1: Задать тестовые учётные данные администратора в `IntegrationTest`**

Заменить `@SpringBootTest` на:

```java
@SpringBootTest(properties = { "crm.admin.login=admin", "crm.admin.password=admin-test-password" })
```

- [ ] **Step 2: Написать падающий тест `AdminBootstrapTest`**

```java
package com.vibe.crm.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.vibe.crm.IntegrationTest;

@IntegrationTest
class AdminBootstrapTest {

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder encoder;

	@BeforeEach
	void clean() {
		users.deleteAll();
	}

	@Test
	void createsAdminWhenNoUsers() {
		new AdminBootstrap(users, encoder, "Boss", "pass12345", "Администратор").ensureAdmin();

		User admin = users.findByLogin("boss").orElseThrow();
		assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
		assertThat(encoder.matches("pass12345", admin.getPasswordHash())).isTrue();
	}

	@Test
	void doesNothingWhenUsersExist() {
		users.save(new User("someone", "h", "Кто-то", Role.MANAGER));

		new AdminBootstrap(users, encoder, "", "", "Администратор").ensureAdmin();

		assertThat(users.count()).isEqualTo(1);
	}

	@Test
	void failsWhenNoUsersAndNoCredentials() {
		assertThatThrownBy(() -> new AdminBootstrap(users, encoder, "", "", "Администратор").ensureAdmin())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("CRM_ADMIN_LOGIN");
	}

}
```

- [ ] **Step 3: Запустить и убедиться, что падает**

Run: `cd backend && ./mvnw -q test -Dtest=AdminBootstrapTest`
Expected: ошибка компиляции — нет `AdminBootstrap`.

- [ ] **Step 4: Создать `users/AdminBootstrap.java`**

```java
package com.vibe.crm.users;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Создаёт первого администратора из CRM_ADMIN_LOGIN / CRM_ADMIN_PASSWORD, если пользователей ещё нет. */
@Component
public class AdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

	private final UserRepository users;

	private final PasswordEncoder encoder;

	private final String login;

	private final String password;

	private final String displayName;

	public AdminBootstrap(UserRepository users, PasswordEncoder encoder, @Value("${crm.admin.login:}") String login,
			@Value("${crm.admin.password:}") String password,
			@Value("${crm.admin.display-name:Администратор}") String displayName) {
		this.users = users;
		this.encoder = encoder;
		this.login = login;
		this.password = password;
		this.displayName = displayName;
	}

	@Override
	public void run(ApplicationArguments args) {
		ensureAdmin();
	}

	@Transactional
	public void ensureAdmin() {
		if (users.count() > 0) {
			return;
		}
		if (login.isBlank() || password.isBlank()) {
			throw new IllegalStateException(
					"В системе нет пользователей. Задайте CRM_ADMIN_LOGIN и CRM_ADMIN_PASSWORD в .env, чтобы создать первого администратора.");
		}
		users.save(new User(login, encoder.encode(password), displayName, Role.ADMIN));
		log.info("Создан первый администратор с логином '{}'", User.normalizeLogin(login));
	}

}
```

- [ ] **Step 5: Добавить свойства в `application.yml`** (в корень файла):

```yaml
crm:
  admin:
    login: ${CRM_ADMIN_LOGIN:}
    password: ${CRM_ADMIN_PASSWORD:}
```

- [ ] **Step 6: Дописать в `.env.example`**

```
# Первый администратор CRM (используется, только пока в системе нет пользователей)
CRM_ADMIN_LOGIN=admin
CRM_ADMIN_PASSWORD=change-me
# Токен бота-секретаря из @BotFather
TELEGRAM_BOT_TOKEN=
```

- [ ] **Step 7: Запустить тесты**

Run: `cd backend && ./mvnw -q verify`
Expected: все тесты зелёные (включая `AdminBootstrapTest`: 3 теста).

- [ ] **Step 8: Сгенерировать пароль администратора для рабочего `.env`** (не коммитится):

Run: `cd /Users/andrey/repository/Vibe/crm && grep -q '^CRM_ADMIN_LOGIN=' .env || printf 'CRM_ADMIN_LOGIN=admin\nCRM_ADMIN_PASSWORD=%s\n' "$(openssl rand -base64 12 | tr -d '/+=')" >> .env && grep CRM_ADMIN .env`
Expected: две строки `CRM_ADMIN_LOGIN=admin`, `CRM_ADMIN_PASSWORD=...` — сообщить пользователю логин и пароль.

- [ ] **Step 9: Commit**

```bash
git add backend .env.example
git commit -m "Создавать первого администратора из переменных окружения"
```

---

### Task 5: Управление пользователями администратором

**Files:**
- Create: `backend/src/main/java/com/vibe/crm/users/UserService.java`
- Create: `backend/src/main/java/com/vibe/crm/users/AdminUserController.java`
- Create: `backend/src/main/java/com/vibe/crm/users/ManagerController.java`
- Create: `backend/src/main/java/com/vibe/crm/users/UserDto.java`
- Test: `backend/src/test/java/com/vibe/crm/users/AdminUserControllerTest.java`

**Interfaces:**
- Consumes: `User`, `UserRepository`, `Role`, `PasswordEncoder`, `ApiException`, `CurrentUser`, `CrmUserPrincipal` (Tasks 2–3); `FindByIndexNameSessionRepository` (Spring Session).
- Produces:
  - `record UserDto(Long id, String login, String displayName, Role role, boolean active, Instant createdAt)`; `static UserDto of(User)`.
  - `record ManagerDto(Long id, String displayName)` (в `ManagerController`).
  - `UserService`: `List<UserDto> list()`, `UserDto create(String login, String password, String displayName, Role role)`, `UserDto update(Long id, String displayName, Role role)`, `void changePassword(Long id, String password)`, `UserDto setActive(Long id, boolean active)`, `List<User> activeUsers()`, `User get(Long id)`.
  - HTTP (только ADMIN): `GET /api/admin/users`; `POST /api/admin/users {login, password, displayName, role}` → 201; `PATCH /api/admin/users/{id} {displayName, role}`; `PUT /api/admin/users/{id}/password {password}` → 204; `POST /api/admin/users/{id}/block` / `/unblock`.
  - HTTP (любой вошедший): `GET /api/users/managers` → `[{id, displayName}]` — активные пользователи.
  - Правила: логин 3–64 символа `[a-z0-9._-]`, уникален (409 «Такой логин уже занят»); пароль ≥ 8 символов; имя 1–100; нельзя блокировать себя / снимать с себя роль ADMIN («Нельзя заблокировать или понизить самого себя»); нельзя оставить систему без активного ADMIN («В системе должен остаться хотя бы один активный администратор»); при блокировке удаляются все сессии пользователя.
  - `TestLogin.as(MockMvc mvc, String login, String password)` → `jakarta.servlet.http.Cookie` (cookie `SESSION`; выполняет вход, проверяет 200). Запросы под пользователем: `.cookie(session)`.

- [ ] **Step 1: Написать падающий тест `AdminUserControllerTest`**

```java
package com.vibe.crm.users;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.vibe.crm.IntegrationTest;
import com.vibe.crm.TestLogin;

import jakarta.servlet.http.Cookie;

@IntegrationTest
class AdminUserControllerTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder encoder;

	Cookie admin;

	Long adminId;

	@BeforeEach
	void setUp() throws Exception {
		users.deleteAll();
		adminId = users.save(new User("boss", encoder.encode("boss-pass-1"), "Босс", Role.ADMIN)).getId();
		admin = TestLogin.as(mvc, "boss", "boss-pass-1");
	}

	private String json(String s) {
		return s.replace('\'', '"');
	}

	@Test
	void createListAndLoginAsNewManager() throws Exception {
		mvc.perform(post("/api/admin/users").cookie(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json("{'login':'Maria','password':'maria-pass','displayName':'Мария К.','role':'MANAGER'}")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.login").value("maria"))
			.andExpect(jsonPath("$.active").value(true));

		mvc.perform(get("/api/admin/users").cookie(admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2));

		TestLogin.as(mvc, "maria", "maria-pass");
	}

	@Test
	void duplicateLoginIsConflict() throws Exception {
		mvc.perform(post("/api/admin/users").cookie(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json("{'login':'BOSS','password':'whatever1','displayName':'X','role':'MANAGER'}")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value("Такой логин уже занят"));
	}

	@Test
	void validationErrorsAreReportedPerField() throws Exception {
		mvc.perform(post("/api/admin/users").cookie(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json("{'login':'a b','password':'short','displayName':'','role':'MANAGER'}")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.login").exists())
			.andExpect(jsonPath("$.fields.password").exists())
			.andExpect(jsonPath("$.fields.displayName").exists());
	}

	@Test
	void blockedUserLosesSessionAndCannotLogin() throws Exception {
		Long id = users.save(new User("oleg", encoder.encode("oleg-pass-1"), "Олег Д.", Role.MANAGER)).getId();
		Cookie oleg = TestLogin.as(mvc, "oleg", "oleg-pass-1");

		mvc.perform(post("/api/admin/users/{id}/block", id).cookie(admin).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(false));

		mvc.perform(get("/api/auth/me").cookie(oleg)).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json("{'login':'oleg','password':'oleg-pass-1'}")))
			.andExpect(status().isUnauthorized());

		mvc.perform(post("/api/admin/users/{id}/unblock", id).cookie(admin).with(csrf()))
			.andExpect(jsonPath("$.active").value(true));
		TestLogin.as(mvc, "oleg", "oleg-pass-1");
	}

	@Test
	void cannotBlockOrDemoteSelf() throws Exception {
		mvc.perform(post("/api/admin/users/{id}/block", adminId).cookie(admin).with(csrf()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value("Нельзя заблокировать или понизить самого себя"));
		mvc.perform(patch("/api/admin/users/{id}", adminId).cookie(admin).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(json("{'displayName':'Босс','role':'MANAGER'}")))
			.andExpect(status().isConflict());
	}

	@Test
	void cannotDemoteLastActiveAdmin() throws Exception {
		Long other = users.save(new User("admin2", encoder.encode("admin2-pass"), "Админ 2", Role.ADMIN)).getId();
		Cookie admin2 = TestLogin.as(mvc, "admin2", "admin2-pass");
		// admin2 блокирует boss — можно, остаётся admin2
		mvc.perform(post("/api/admin/users/{id}/block", adminId).cookie(admin2).with(csrf()))
			.andExpect(status().isOk());
		// разблокируем boss и понижаем admin2 от имени boss — можно, остаётся boss
		mvc.perform(post("/api/admin/users/{id}/unblock", adminId).cookie(admin2).with(csrf()))
			.andExpect(status().isOk());
		Cookie boss = TestLogin.as(mvc, "boss", "boss-pass-1");
		mvc.perform(patch("/api/admin/users/{id}", other).cookie(boss).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(json("{'displayName':'Админ 2','role':'MANAGER'}")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("MANAGER"));
	}

	@Test
	void changePassword() throws Exception {
		Long id = users.save(new User("oleg", encoder.encode("oleg-pass-1"), "Олег Д.", Role.MANAGER)).getId();

		mvc.perform(put("/api/admin/users/{id}/password", id).cookie(admin).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(json("{'password':'new-pass-22'}")))
			.andExpect(status().isNoContent());

		TestLogin.as(mvc, "oleg", "new-pass-22");
	}

	@Test
	void managersListIsAvailableToManagerAndContainsOnlyActive() throws Exception {
		users.save(new User("maria", encoder.encode("maria-pass"), "Мария К.", Role.MANAGER));
		User blocked = new User("oleg", encoder.encode("oleg-pass-1"), "Олег Д.", Role.MANAGER);
		blocked.setActive(false);
		users.save(blocked);
		Cookie maria = TestLogin.as(mvc, "maria", "maria-pass");

		mvc.perform(get("/api/users/managers").cookie(maria))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].displayName").value("Босс"))
			.andExpect(jsonPath("$[1].displayName").value("Мария К."));
	}

}
```

- [ ] **Step 2: Запустить и убедиться, что падает**

Run: `cd backend && ./mvnw -q test -Dtest=AdminUserControllerTest`
Expected: падение — эндпоинтов нет (404/ошибка компиляции).

- [ ] **Step 3: Создать `users/UserDto.java`**

```java
package com.vibe.crm.users;

import java.time.Instant;

public record UserDto(Long id, String login, String displayName, Role role, boolean active, Instant createdAt) {

	public static UserDto of(User u) {
		return new UserDto(u.getId(), u.getLogin(), u.getDisplayName(), u.getRole(), u.isActive(), u.getCreatedAt());
	}

}
```

- [ ] **Step 4: Создать `users/UserService.java`**

```java
package com.vibe.crm.users;

import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.auth.CurrentUser;
import com.vibe.crm.common.ApiException;

@Service
@Transactional
public class UserService {

	private final UserRepository users;

	private final PasswordEncoder encoder;

	private final FindByIndexNameSessionRepository<? extends Session> sessions;

	public UserService(UserRepository users, PasswordEncoder encoder,
			FindByIndexNameSessionRepository<? extends Session> sessions) {
		this.users = users;
		this.encoder = encoder;
		this.sessions = sessions;
	}

	@Transactional(readOnly = true)
	public List<UserDto> list() {
		return users.findAllByOrderByDisplayNameAsc().stream().map(UserDto::of).toList();
	}

	@Transactional(readOnly = true)
	public List<User> activeUsers() {
		return users.findByActiveTrueOrderByDisplayNameAsc();
	}

	@Transactional(readOnly = true)
	public User get(Long id) {
		return users.findById(id).orElseThrow(() -> ApiException.notFound("Пользователь не найден"));
	}

	public UserDto create(String login, String password, String displayName, Role role) {
		String normalized = User.normalizeLogin(login);
		if (users.existsByLogin(normalized)) {
			throw ApiException.conflict("Такой логин уже занят");
		}
		return UserDto.of(users.save(new User(normalized, encoder.encode(password), displayName, role)));
	}

	public UserDto update(Long id, String displayName, Role role) {
		User user = get(id);
		if (user.getRole() == Role.ADMIN && role != Role.ADMIN) {
			guardNotSelf(user);
			guardNotLastAdmin(user);
		}
		user.setDisplayName(displayName);
		user.setRole(role);
		return UserDto.of(user);
	}

	public void changePassword(Long id, String password) {
		get(id).setPasswordHash(encoder.encode(password));
	}

	public UserDto setActive(Long id, boolean active) {
		User user = get(id);
		if (!active) {
			guardNotSelf(user);
			if (user.getRole() == Role.ADMIN) {
				guardNotLastAdmin(user);
			}
			sessions.findByPrincipalName(user.getLogin()).keySet().forEach(sessions::deleteById);
		}
		user.setActive(active);
		return UserDto.of(user);
	}

	private void guardNotSelf(User user) {
		if (user.getId().equals(CurrentUser.get().id())) {
			throw ApiException.conflict("Нельзя заблокировать или понизить самого себя");
		}
	}

	private void guardNotLastAdmin(User user) {
		if (user.isActive() && users.countByRoleAndActiveTrue(Role.ADMIN) <= 1) {
			throw ApiException.conflict("В системе должен остаться хотя бы один активный администратор");
		}
	}

}
```

- [ ] **Step 5: Создать `users/AdminUserController.java`**

```java
package com.vibe.crm.users;

import java.util.List;

import org.springframework.http.HttpStatus;
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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

	public record CreateUserRequest(
			@NotBlank(message = "Введите логин") @Pattern(regexp = "^\\s*[A-Za-z0-9._-]{3,64}\\s*$",
					message = "Логин: 3–64 символа, латиница, цифры, точка, дефис, подчёркивание") String login,
			@NotNull(message = "Введите пароль") @Size(min = 8, max = 72,
					message = "Пароль: от 8 до 72 символов") String password,
			@NotBlank(message = "Введите имя") @Size(max = 100, message = "Имя: до 100 символов") String displayName,
			@NotNull(message = "Выберите роль") Role role) {
	}

	public record UpdateUserRequest(
			@NotBlank(message = "Введите имя") @Size(max = 100, message = "Имя: до 100 символов") String displayName,
			@NotNull(message = "Выберите роль") Role role) {
	}

	public record PasswordRequest(@NotNull(message = "Введите пароль") @Size(min = 8, max = 72,
			message = "Пароль: от 8 до 72 символов") String password) {
	}

	private final UserService service;

	public AdminUserController(UserService service) {
		this.service = service;
	}

	@GetMapping
	public List<UserDto> list() {
		return service.list();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public UserDto create(@Valid @RequestBody CreateUserRequest body) {
		return service.create(body.login(), body.password(), body.displayName(), body.role());
	}

	@PatchMapping("/{id}")
	public UserDto update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest body) {
		return service.update(id, body.displayName(), body.role());
	}

	@PutMapping("/{id}/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void changePassword(@PathVariable Long id, @Valid @RequestBody PasswordRequest body) {
		service.changePassword(id, body.password());
	}

	@PostMapping("/{id}/block")
	public UserDto block(@PathVariable Long id) {
		return service.setActive(id, false);
	}

	@PostMapping("/{id}/unblock")
	public UserDto unblock(@PathVariable Long id) {
		return service.setActive(id, true);
	}

}
```

- [ ] **Step 6: Создать `users/ManagerController.java`**

```java
package com.vibe.crm.users;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class ManagerController {

	public record ManagerDto(Long id, String displayName) {
	}

	private final UserService service;

	public ManagerController(UserService service) {
		this.service = service;
	}

	@GetMapping("/managers")
	public List<ManagerDto> managers() {
		return service.activeUsers().stream().map(u -> new ManagerDto(u.getId(), u.getDisplayName())).toList();
	}

}
```

- [ ] **Step 7: Запустить тесты**

Run: `cd backend && ./mvnw -q test -Dtest=AdminUserControllerTest`
Expected: `Tests run: 8, Failures: 0, Errors: 0`.

Если `blockedUserLosesSessionAndCannotLogin` показывает, что после блокировки `/api/auth/me` со старой cookie возвращает 200 (сессия не нашлась по имени пользователя через `FindByIndexNameSessionRepository`) — нужна проверка активности на каждый запрос: фильтр после аутентификации, который для вошедшего пользователя перечитывает `users.findById(id)` и при `!active` очищает контекст, инвалидирует сессию и отвечает 401. Тогда добавить:

```java
package com.vibe.crm.auth;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.vibe.crm.users.User;
import com.vibe.crm.users.UserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/** Разлогинивает заблокированного пользователя на следующем же запросе. */
class ActiveUserFilter extends OncePerRequestFilter {

	private final UserRepository users;

	ActiveUserFilter(UserRepository users) {
		this.users = users;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof CrmUserPrincipal p
				&& !users.findById(p.id()).map(User::isActive).orElse(false)) {
			SecurityContextHolder.clearContext();
			HttpSession session = request.getSession(false);
			if (session != null) {
				session.invalidate();
			}
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			return;
		}
		chain.doFilter(request, response);
	}

}
```

и в `SecurityConfig.securityFilterChain` (параметр метода `UserRepository users`):

```java
http.addFilterAfter(new ActiveUserFilter(users), org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class);
```

Перезапустить тест.

- [ ] **Step 8: Прогнать все тесты**

Run: `cd backend && ./mvnw -q verify`
Expected: все тесты зелёные.

- [ ] **Step 9: Commit**

```bash
git add backend
git commit -m "Добавить управление пользователями администратором"
```

---

### Task 6: Проверка на запущенном приложении

- [ ] **Step 1: Перезапустить бекенд** (остановить запущенный, запустить `cd backend && ./mvnw spring-boot:run` в фоне). В логе: `Создан первый администратор с логином 'admin'`.

- [ ] **Step 2: Проверить вход curl'ом с CSRF**

```bash
J=/tmp/crm-cookies; rm -f $J
curl -s -c $J -b $J localhost:8080/api/auth/me -o /dev/null -w '%{http_code}\n'      # 401
XSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -c $J -b $J -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
  -d "{\"login\":\"admin\",\"password\":\"$(grep CRM_ADMIN_PASSWORD .env | cut -d= -f2)\"}" localhost:8080/api/auth/login   # {"id":..,"role":"ADMIN"}
curl -s -b $J localhost:8080/api/auth/me      # тот же пользователь
```

Expected: 401, затем JSON администратора дважды.

- [ ] **Step 3: Push** — по скиллу `github-push` в `dev`.
