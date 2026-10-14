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
import com.vibe.crm.TestData;
import com.vibe.crm.TestLogin;
import com.vibe.crm.users.Role;
import com.vibe.crm.users.User;
import com.vibe.crm.users.UserRepository;

import jakarta.servlet.http.Cookie;

@IntegrationTest
class AuthControllerTest {

	@Autowired
	TestData data;

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder encoder;

	@BeforeEach
	void setUp() {
		data.clearUsers();
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
