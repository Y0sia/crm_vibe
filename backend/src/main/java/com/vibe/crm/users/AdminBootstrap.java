package com.vibe.crm.users;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Создаёт первого администратора из CRM_ADMIN_LOGIN / CRM_ADMIN_PASSWORD, если пользователей ещё нет. */
@Component
public class AdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

	private final UserRepository users;

	private final PasswordEncoder encoder;

	private final String login;

	private final String password;

	private final String displayName;

	public AdminBootstrap(UserRepository users, PasswordEncoder encoder, @Value("${crm.admin.login:}") String login,
			@Value("${crm.admin.password:}") String password,
			@Value("${crm.admin.display-name:Администратор}") String displayName) {
		this.users = users;
		this.encoder = encoder;
		this.login = login;
		this.password = password;
		this.displayName = displayName;
	}

	@Override
	public void run(ApplicationArguments args) {
		ensureAdmin();
	}

	@Transactional
	public void ensureAdmin() {
		if (users.count() > 0) {
			return;
		}
		if (login.isBlank() || password.isBlank()) {
			throw new IllegalStateException(
					"В системе нет пользователей. Задайте CRM_ADMIN_LOGIN и CRM_ADMIN_PASSWORD в .env, чтобы создать первого администратора.");
		}
		users.save(new User(login, encoder.encode(password), displayName, Role.ADMIN));
		log.info("Создан первый администратор с логином '{}'", User.normalizeLogin(login));
	}

}
