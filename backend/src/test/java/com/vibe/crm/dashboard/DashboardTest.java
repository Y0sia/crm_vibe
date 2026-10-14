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
