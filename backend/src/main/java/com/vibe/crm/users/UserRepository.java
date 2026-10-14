package com.vibe.crm.users;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByLogin(String login);

	boolean existsByLogin(String login);

	long countByRoleAndActiveTrue(Role role);

	List<User> findAllByOrderByDisplayNameAsc();

	List<User> findByActiveTrueOrderByDisplayNameAsc();

}
