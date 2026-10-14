package com.vibe.crm.leads;

import java.time.Instant;

import com.vibe.crm.users.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Запись истории лида. Значения хранятся текстом (имена на момент события),
 * чтобы история читалась после переименования или удаления статусов и тегов.
 */
@Entity
@Table(name = "lead_events")
public class LeadEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "lead_id", nullable = false)
	private Lead lead;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "actor_id")
	private User actor;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private LeadEventType type;

	@Column(name = "old_value")
	private String oldValue;

	@Column(name = "new_value")
	private String newValue;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected LeadEvent() {
	}

	public LeadEvent(Lead lead, User actor, LeadEventType type, String oldValue, String newValue, Instant createdAt) {
		this.lead = lead;
		this.actor = actor;
		this.type = type;
		this.oldValue = oldValue;
		this.newValue = newValue;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public Lead getLead() {
		return lead;
	}

	public User getActor() {
		return actor;
	}

	public LeadEventType getType() {
		return type;
	}

	public String getOldValue() {
		return oldValue;
	}

	public String getNewValue() {
		return newValue;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
