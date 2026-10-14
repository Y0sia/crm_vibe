package com.vibe.crm.auth;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.vibe.crm.users.Role;
import com.vibe.crm.users.User;

/** Вошедший пользователь. Хранится в сессии, поэтому содержит только неизменяемые простые поля. */
public record CrmUserPrincipal(Long id, String login, String passwordHash, String displayName, Role role,
		boolean active) implements UserDetails {

	public static CrmUserPrincipal of(User user) {
		return new CrmUserPrincipal(user.getId(), user.getLogin(), user.getPasswordHash(), user.getDisplayName(),
				user.getRole(), user.isActive());
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	@Override
	public String getPassword() {
		return passwordHash;
	}

	@Override
	public String getUsername() {
		return login;
	}

	@Override
	public boolean isEnabled() {
		return active;
	}

	public boolean isAdmin() {
		return role == Role.ADMIN;
	}

	/** Копия без хеша пароля — именно она кладётся в сессию. */
	public CrmUserPrincipal withoutPassword() {
		return new CrmUserPrincipal(id, login, null, displayName, role, active);
	}

}
