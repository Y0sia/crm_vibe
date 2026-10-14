package com.vibe.crm.messages;

import java.time.Instant;

import com.vibe.crm.leads.Lead;
import com.vibe.crm.users.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Один ответ лиду: сколько секунд лид ждал. author == null — ответ из приложения Telegram. */
@Entity
@Table(name = "response_events")
public class ResponseEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "lead_id", nullable = false)
	private Lead lead;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "author_id")
	private User author;

	@Column(name = "waited_seconds", nullable = false)
	private long waitedSeconds;

	@Column(name = "answered_at", nullable = false)
	private Instant answeredAt;

	protected ResponseEvent() {
	}

	public ResponseEvent(Lead lead, User author, long waitedSeconds, Instant answeredAt) {
		this.lead = lead;
		this.author = author;
		this.waitedSeconds = waitedSeconds;
		this.answeredAt = answeredAt;
	}

	public Long getId() {
		return id;
	}

	public Lead getLead() {
		return lead;
	}

	public User getAuthor() {
		return author;
	}

	public long getWaitedSeconds() {
		return waitedSeconds;
	}

	public Instant getAnsweredAt() {
		return answeredAt;
	}

}
