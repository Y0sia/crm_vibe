package com.vibe.crm.users;

import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.auth.CurrentUser;
import com.vibe.crm.common.ApiException;

@Service
@Transactional
public class UserService {

	private final UserRepository users;

	private final PasswordEncoder encoder;

	private final FindByIndexNameSessionRepository<? extends Session> sessions;

	public UserService(UserRepository users, PasswordEncoder encoder,
			FindByIndexNameSessionRepository<? extends Session> sessions) {
		this.users = users;
		this.encoder = encoder;
		this.sessions = sessions;
	}

	@Transactional(readOnly = true)
	public List<UserDto> list() {
		return users.findAllByOrderByDisplayNameAsc().stream().map(UserDto::of).toList();
	}

	@Transactional(readOnly = true)
	public List<User> activeUsers() {
		return users.findByActiveTrueOrderByDisplayNameAsc();
	}

	@Transactional(readOnly = true)
	public User get(Long id) {
		return users.findById(id).orElseThrow(() -> ApiException.notFound("Пользователь не найден"));
	}

	public UserDto create(String login, String password, String displayName, Role role) {
		String normalized = User.normalizeLogin(login);
		if (users.existsByLogin(normalized)) {
			throw ApiException.conflict("Такой логин уже занят");
		}
		return UserDto.of(users.save(new User(normalized, encoder.encode(password), displayName, role)));
	}

	public UserDto update(Long id, String displayName, Role role) {
		User user = get(id);
		if (user.getRole() == Role.ADMIN && role != Role.ADMIN) {
			guardNotSelf(user);
			guardNotLastAdmin(user);
		}
		if (user.getRole() != role) {
			endSessions(user);
		}
		user.setDisplayName(displayName);
		user.setRole(role);
		return UserDto.of(user);
	}

	public void changePassword(Long id, String password) {
		User user = get(id);
		user.setPasswordHash(encoder.encode(password));
		endSessions(user);
	}

	public UserDto setActive(Long id, boolean active) {
		User user = get(id);
		if (!active) {
			guardNotSelf(user);
			if (user.getRole() == Role.ADMIN) {
				guardNotLastAdmin(user);
			}
			endSessions(user);
		}
		user.setActive(active);
		return UserDto.of(user);
	}

	/** Права и пароль лежат в сессии, поэтому после их смены пользователь должен войти заново. */
	private void endSessions(User user) {
		sessions.findByPrincipalName(user.getLogin()).keySet().forEach(sessions::deleteById);
	}

	private void guardNotSelf(User user) {
		if (user.getId().equals(CurrentUser.get().id())) {
			throw ApiException.conflict("Нельзя заблокировать или понизить самого себя");
		}
	}

	private void guardNotLastAdmin(User user) {
		if (user.isActive() && users.countByRoleAndActiveTrue(Role.ADMIN) <= 1) {
			throw ApiException.conflict("В системе должен остаться хотя бы один активный администратор");
		}
	}

}
