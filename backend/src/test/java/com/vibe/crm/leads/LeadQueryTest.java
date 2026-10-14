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
