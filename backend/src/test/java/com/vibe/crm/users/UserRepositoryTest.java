package com.vibe.crm.users;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.IntegrationTest;

@IntegrationTest
@Transactional
class UserRepositoryTest {

	@Autowired
	UserRepository users;

	@BeforeEach
	void clean() {
		users.deleteAll();
	}

	@Test
	void loginIsStoredLowercaseAndFoundByIt() {
		users.save(new User("  Maria.K ", "hash", "Мария К.", Role.MANAGER));

		assertThat(users.findByLogin("maria.k")).get().satisfies(u -> {
			assertThat(u.getDisplayName()).isEqualTo("Мария К.");
			assertThat(u.getRole()).isEqualTo(Role.MANAGER);
			assertThat(u.isActive()).isTrue();
			assertThat(u.getCreatedAt()).isNotNull();
		});
		assertThat(users.existsByLogin("maria.k")).isTrue();
	}

	@Test
	void countsOnlyActiveUsersOfRole() {
		users.save(new User("admin1", "h", "Админ 1", Role.ADMIN));
		User blocked = new User("admin2", "h", "Админ 2", Role.ADMIN);
		blocked.setActive(false);
		users.save(blocked);
		users.save(new User("mgr", "h", "Менеджер", Role.MANAGER));

		assertThat(users.countByRoleAndActiveTrue(Role.ADMIN)).isEqualTo(1);
	}

}
