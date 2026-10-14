package com.vibe.crm.leads;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.users.UserRepository;

@Service
public class LeadHistory {

	private final LeadEventRepository events;

	private final UserRepository users;

	public LeadHistory(LeadEventRepository events, UserRepository users) {
		this.events = events;
		this.users = users;
	}

	/** actorId == null — событие системы (например, лид создан из Telegram). */
	@Transactional
	public void record(Lead lead, Long actorId, LeadEventType type, String oldValue, String newValue) {
		events.save(new LeadEvent(lead, actorId == null ? null : users.getReferenceById(actorId), type, oldValue,
				newValue, Instant.now()));
	}

}
