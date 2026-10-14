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
