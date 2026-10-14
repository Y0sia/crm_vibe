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
