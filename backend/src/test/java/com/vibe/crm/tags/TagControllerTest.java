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
