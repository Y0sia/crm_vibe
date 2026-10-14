package com.vibe.crm.users;

import java.time.Instant;

public record UserDto(Long id, String login, String displayName, Role role, boolean active, Instant createdAt) {

	public static UserDto of(User u) {
		return new UserDto(u.getId(), u.getLogin(), u.getDisplayName(), u.getRole(), u.isActive(), u.getCreatedAt());
	}

}
