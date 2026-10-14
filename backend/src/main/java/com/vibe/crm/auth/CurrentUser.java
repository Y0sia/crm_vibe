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
