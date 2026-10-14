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
