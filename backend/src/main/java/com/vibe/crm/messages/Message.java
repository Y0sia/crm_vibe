package com.vibe.crm.messages;

import java.time.Instant;

import com.vibe.crm.leads.Lead;
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

/** Сообщение переписки с лидом. author заполнен, только если ответ отправлен из CRM. */
@Entity
@Table(name = "messages")
public class Message {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "lead_id", nullable = false)
	private Lead lead;

	@Column(name = "telegram_message_id", nullable = false)
	private long telegramMessageId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 8)
	private Direction direction;

	@Column(nullable = false, columnDefinition = "text")
	private String text;

	@Column(name = "sent_at", nullable = false)
	private Instant sentAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "author_id")
	private User author;

	@Column(name = "edited_at")
	private Instant editedAt;

	@Column(nullable = false)
	private boolean deleted;

	protected Message() {
	}

	public Message(Lead lead, long telegramMessageId, Direction direction, String text, Instant sentAt, User author) {
		this.lead = lead;
		this.telegramMessageId = telegramMessageId;
		this.direction = direction;
		this.text = text;
		this.sentAt = sentAt;
		this.author = author;
	}

	public void edit(String text, Instant editedAt) {
		this.text = text;
		this.editedAt = editedAt;
	}

	public void markDeleted() {
		this.deleted = true;
	}

	public Long getId() {
		return id;
	}

	public Lead getLead() {
		return lead;
	}

	public long getTelegramMessageId() {
		return telegramMessageId;
	}

	public Direction getDirection() {
		return direction;
	}

	public String getText() {
		return text;
	}

	public Instant getSentAt() {
		return sentAt;
	}

	public User getAuthor() {
		return author;
	}

	public Instant getEditedAt() {
		return editedAt;
	}

	public boolean isDeleted() {
		return deleted;
	}

}
