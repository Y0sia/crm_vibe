package com.vibe.crm.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.vibe.crm.IntegrationTest;
import com.vibe.crm.TestData;

@IntegrationTest
class AdminBootstrapTest {

	@Autowired
	TestData data;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder encoder;

	@BeforeEach
	void clean() {
		data.clearUsers();
	}

	@Test
	void createsAdminWhenNoUsers() {
		new AdminBootstrap(users, encoder, "Boss", "pass12345", "Администратор").ensureAdmin();

		User admin = users.findByLogin("boss").orElseThrow();
		assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
		assertThat(encoder.matches("pass12345", admin.getPasswordHash())).isTrue();
	}

	@Test
	void doesNothingWhenUsersExist() {
		users.save(new User("someone", "h", "Кто-то", Role.MANAGER));

		new AdminBootstrap(users, encoder, "", "", "Администратор").ensureAdmin();

		assertThat(users.count()).isEqualTo(1);
	}

	@Test
	void failsWhenNoUsersAndNoCredentials() {
		assertThatThrownBy(() -> new AdminBootstrap(users, encoder, "", "", "Администратор").ensureAdmin())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("CRM_ADMIN_LOGIN");
	}

}
