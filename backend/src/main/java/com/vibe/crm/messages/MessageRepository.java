package com.vibe.crm.messages;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vibe.crm.leads.Lead;

public interface MessageRepository extends JpaRepository<Message, Long> {

	boolean existsByLeadAndTelegramMessageId(Lead lead, long telegramMessageId);

	Optional<Message> findByLeadAndTelegramMessageId(Lead lead, long telegramMessageId);

	List<Message> findByLeadAndTelegramMessageIdIn(Lead lead, Collection<Long> telegramMessageIds);

	List<Message> findByLeadOrderBySentAtAscIdAsc(Lead lead);

	Optional<Message> findFirstByLeadOrderBySentAtDescIdDesc(Lead lead);

}
