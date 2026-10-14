package com.vibe.crm.telegram;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessConnectionRepository extends JpaRepository<BusinessConnection, String> {

	Optional<BusinessConnection> findFirstByEnabledTrueOrderByUpdatedAtDesc();

}
