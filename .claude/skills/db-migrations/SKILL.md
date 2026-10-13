---
name: db-migrations
description: Изменение структуры БД в бекенде CRM только через миграции Liquibase. Используй этот скилл ВСЕГДА, когда задача затрагивает схему базы данных — новая сущность или таблица, новое/удалённое/переименованное поле, изменение типа колонки, индекс, внешний ключ, связь между сущностями (@OneToMany, @ManyToOne и т.д.), уникальное ограничение, NOT NULL, значение по умолчанию. Срабатывает даже если пользователь не говорит слово «миграция» — например, «добавь клиенту поле телефон», «сделай сущность Сделка», «свяжи заказы с клиентами», «поменяй тип суммы на decimal».
---

# Миграции БД через Liquibase

В проекте схема БД принадлежит Liquibase, а не Hibernate: в `application.yml` стоит
`spring.jpa.hibernate.ddl-auto: validate`. Hibernate только сверяет сущности с реальными
таблицами и падает при старте, если они расходятся. Поэтому любое изменение JPA-сущности,
влияющее на колонки, должно сопровождаться миграцией в том же коммите — иначе приложение
не запустится, а на других окружениях схема разъедется.

Никогда не меняй схему руками через `psql`, `ddl-auto: update` или SQL из кода приложения:
такие изменения не воспроизводятся на других машинах и не попадают в историю.

## Где лежат миграции

```
backend/src/main/resources/db/changelog/
├── db.changelog-master.yaml      # includeAll: changes/ — подключает всё автоматически
└── changes/
    ├── 001-init.yaml
    └── 002-....yaml              # новые файлы сюда
```

`includeAll` применяет файлы в алфавитном порядке, поэтому номер с ведущими нулями
определяет порядок выполнения. Master-файл править не нужно.

## Процесс

1. **Найди следующий номер:** `ls backend/src/main/resources/db/changelog/changes/` —
   возьми максимальный и прибавь 1 (`002`, `003`, ...).
2. **Создай новый файл** `NNN-<что-делает-kebab-case>.yaml`, например
   `002-create-clients.yaml`, `003-add-phone-to-clients.yaml`.
   Одна миграция — одно логическое изменение. Связанные шаги (таблица + индекс + FK)
   можно держать в одном файле несколькими changeSet'ами.
3. **Измени JPA-сущность** так, чтобы она в точности соответствовала миграции
   (имена колонок, типы, nullable, длины).
4. **Проверь** (см. ниже) и коммить сущность и миграцию вместе.

## Правила

- **Не редактируй уже применённые changeSet'ы.** Liquibase хранит их контрольные суммы
  в таблице `databasechangelog`; изменённый файл даст ошибку checksum при старте у всех,
  у кого миграция уже прошла. Нужно поправить — пиши новую миграцию поверх.
  Исключение — миграция создана в текущей задаче и ещё нигде не запушена: её можно
  исправить, но тогда на локальной БД её придётся откатить (см. «Если ошибся»).
- **`id` changeSet'а** = имя файла без расширения (для нескольких changeSet'ов в файле —
  с суффиксом: `002-create-clients`, `002-create-clients-index-email`).
  **`author`** — `crm`.
- **Имена в snake_case:** таблицы во множественном числе (`clients`, `deals`),
  колонки `created_at`, `client_id`. Ограничения называй явно: `pk_clients`,
  `fk_deals_client`, `uq_clients_email`, `idx_deals_client_id`.
- **Используй change types Liquibase** (`createTable`, `addColumn`, `addForeignKeyConstraint`,
  `createIndex`, `renameColumn`, `modifyDataType`, ...), а не `sql`, — для них Liquibase
  умеет автоматически генерировать rollback. `sql` — только для того, что нельзя
  выразить иначе, и тогда с явным блоком `rollback`.
- **Данные важнее схемы.** Добавляя NOT NULL колонку в таблицу, где уже могут быть строки,
  задай `defaultValue*` или заполни её отдельным шагом до добавления ограничения.
  Удаление колонки/таблицы безвозвратно уничтожает данные — уточни у пользователя,
  прежде чем писать `dropColumn` / `dropTable`.

## Соответствие типов (PostgreSQL ↔ Java)

| Java | Liquibase `type` |
|---|---|
| `Long` id + `@GeneratedValue(strategy = IDENTITY)` | `bigint` + `autoIncrement: true` |
| `UUID` | `uuid` |
| `String` (`@Column(length = N)`) | `varchar(N)` (по умолчанию в JPA 255) |
| `String` с `@Column(columnDefinition = "text")` / `@Lob` | `text` |
| `BigDecimal` (`precision`, `scale`) | `numeric(p, s)` |
| `Integer` / `Long` | `int` / `bigint` |
| `Boolean` | `boolean` |
| `Instant` / `OffsetDateTime` | `timestamp with time zone` |
| `LocalDateTime` | `timestamp` |
| `LocalDate` | `date` |
| `enum` с `@Enumerated(EnumType.STRING)` | `varchar(N)` |

Для enum всегда `EnumType.STRING`: `ORDINAL` ломает данные при перестановке значений.

## Пример: новая таблица со связью

```yaml
databaseChangeLog:
  - changeSet:
      id: 003-create-deals
      author: crm
      changes:
        - createTable:
            tableName: deals
            columns:
              - column:
                  name: id
                  type: bigint
                  autoIncrement: true
                  constraints:
                    primaryKey: true
                    primaryKeyName: pk_deals
              - column:
                  name: title
                  type: varchar(255)
                  constraints:
                    nullable: false
              - column:
                  name: amount
                  type: numeric(19, 2)
              - column:
                  name: client_id
                  type: bigint
                  constraints:
                    nullable: false
                    foreignKeyName: fk_deals_client
                    references: clients(id)
              - column:
                  name: created_at
                  type: timestamp with time zone
                  defaultValueComputed: now()
                  constraints:
                    nullable: false
        - createIndex:
            tableName: deals
            indexName: idx_deals_client_id
            columns:
              - column:
                  name: client_id
```

## Пример: изменение существующей таблицы

```yaml
databaseChangeLog:
  - changeSet:
      id: 004-add-phone-to-clients
      author: crm
      changes:
        - addColumn:
            tableName: clients
            columns:
              - column:
                  name: phone
                  type: varchar(32)
```

## Проверка

БД должна быть поднята (`docker compose up -d` в корне проекта). Затем:

```bash
cd backend && ./mvnw -q verify
```

Тест `CrmApplicationTests` поднимает контекст: Liquibase применяет миграции к локальной БД,
а Hibernate в режиме `validate` сверяет с ними сущности. Ошибки вида
`Schema-validation: missing column` / `wrong column type` означают, что сущность и
миграция расходятся — исправляй, пока не пройдёт. Если бекенд уже запущен, перезапусти
его, чтобы он подхватил новые миграции.

Убедиться, что миграция применилась:

```bash
docker exec vibe-crm-postgres psql -U crm -d crm -c "select id, filename, dateexecuted from databasechangelog order by orderexecuted"
docker exec vibe-crm-postgres psql -U crm -d crm -c "\d <таблица>"
```

## Если ошибся в ещё не запушенной миграции

Миграция уже применилась к локальной БД, но её нужно поправить:

1. Откати вручную то, что она создала (например, `drop table deals`), и удали её запись:
   `delete from databasechangelog where id = '<id>'`.
2. Исправь файл и снова запусти проверку.

Если миграция уже запушена — не трогай её, пиши новую.
