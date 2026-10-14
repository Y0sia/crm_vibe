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
import com.vibe.crm.TestData;
import com.vibe.crm.TestLogin;

import jakarta.servlet.http.Cookie;

@IntegrationTest
class AdminUserControllerTest {

	@Autowired
	TestData data;

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
		data.clearUsers();
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

	@Test
	void roleOrPasswordChangeEndsUserSessions() throws Exception {
		Long other = users.save(new User("admin2", encoder.encode("admin2-pass"), "Админ 2", Role.ADMIN)).getId();
		Cookie admin2 = TestLogin.as(mvc, "admin2", "admin2-pass");

		mvc.perform(patch("/api/admin/users/{id}", other).cookie(admin).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(json("{'displayName':'Админ 2','role':'MANAGER'}")))
			.andExpect(status().isOk());
		mvc.perform(get("/api/admin/users").cookie(admin2)).andExpect(status().isUnauthorized());

		Cookie again = TestLogin.as(mvc, "admin2", "admin2-pass");
		mvc.perform(put("/api/admin/users/{id}/password", other).cookie(admin).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(json("{'password':'other-pass-3'}")))
			.andExpect(status().isNoContent());
		mvc.perform(get("/api/auth/me").cookie(again)).andExpect(status().isUnauthorized());
	}

	@Test
	void changingOwnPasswordKeepsCurrentSession() throws Exception {
		mvc.perform(put("/api/admin/users/{id}/password", adminId).cookie(admin).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(json("{'password':'boss-new-pass'}")))
			.andExpect(status().isNoContent());

		mvc.perform(get("/api/auth/me").cookie(admin)).andExpect(status().isOk());
		TestLogin.as(mvc, "boss", "boss-new-pass");
		mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json("{'login':'boss','password':'boss-pass-1'}")))
			.andExpect(status().isUnauthorized());
	}

}
