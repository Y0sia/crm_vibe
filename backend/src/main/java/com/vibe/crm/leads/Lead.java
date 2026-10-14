package com.vibe.crm.leads;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import com.vibe.crm.statuses.Status;
import com.vibe.crm.tags.Tag;
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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Человек, написавший в личку рабочему Telegram-аккаунту. */
@Entity
@Table(name = "leads")
public class Lead {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "telegram_user_id", nullable = false, unique = true)
	private long telegramUserId;

	@Column(name = "telegram_chat_id", nullable = false)
	private long telegramChatId;

	@Column(name = "business_connection_id", length = 128)
	private String businessConnectionId;

	@Column(name = "first_name")
	private String firstName;

	@Column(name = "last_name")
	private String lastName;

	@Column(length = 64)
	private String username;

	@Column(length = 32)
	private String phone;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "status_id", nullable = false)
	private Status status;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "manager_id")
	private User manager;

	@Enumerated(EnumType.STRING)
	@Column(length = 16)
	private LeadSource source;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "last_message_at")
	private Instant lastMessageAt;

	@Column(name = "last_message_text", columnDefinition = "text")
	private String lastMessageText;

	@Column(name = "last_incoming_at")
	private Instant lastIncomingAt;

	@Column(name = "awaiting_reply_since")
	private Instant awaitingReplySince;

	@ManyToMany
	@JoinTable(name = "lead_tags", joinColumns = @JoinColumn(name = "lead_id"),
			inverseJoinColumns = @JoinColumn(name = "tag_id"))
	private Set<Tag> tags = new HashSet<>();

	protected Lead() {
	}

	public Lead(long telegramUserId, long telegramChatId, Status status, Instant createdAt) {
		this.telegramUserId = telegramUserId;
		this.telegramChatId = telegramChatId;
		this.status = status;
		this.createdAt = createdAt;
	}

	/** «Имя Фамилия», иначе @username, иначе ID. */
	public String displayName() {
		String full = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
		if (!full.isEmpty()) {
			return full;
		}
		if (username != null && !username.isBlank()) {
			return "@" + username;
		}
		return "ID " + telegramUserId;
	}

	/** Последнее сообщение для списка лидов: обновляется, если это сообщение не старше текущего. */
	public void registerMessage(String text, Instant at) {
		if (lastMessageAt == null || !at.isBefore(lastMessageAt)) {
			lastMessageAt = at;
			lastMessageText = text;
		}
	}

	public Long getId() {
		return id;
	}

	public long getTelegramUserId() {
		return telegramUserId;
	}

	public long getTelegramChatId() {
		return telegramChatId;
	}

	public void setTelegramChatId(long telegramChatId) {
		this.telegramChatId = telegramChatId;
	}

	public String getBusinessConnectionId() {
		return businessConnectionId;
	}

	public void setBusinessConnectionId(String businessConnectionId) {
		this.businessConnectionId = businessConnectionId;
	}

	public String getFirstName() {
		return firstName;
	}

	public void setFirstName(String firstName) {
		this.firstName = firstName;
	}

	public String getLastName() {
		return lastName;
	}

	public void setLastName(String lastName) {
		this.lastName = lastName;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPhone() {
		return phone;
	}

	public void setPhone(String phone) {
		this.phone = phone;
	}

	public Status getStatus() {
		return status;
	}

	public void setStatus(Status status) {
		this.status = status;
	}

	public User getManager() {
		return manager;
	}

	public void setManager(User manager) {
		this.manager = manager;
	}

	public LeadSource getSource() {
		return source;
	}

	public void setSource(LeadSource source) {
		this.source = source;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getLastMessageAt() {
		return lastMessageAt;
	}

	public void setLastMessageAt(Instant lastMessageAt) {
		this.lastMessageAt = lastMessageAt;
	}

	public String getLastMessageText() {
		return lastMessageText;
	}

	public void setLastMessageText(String lastMessageText) {
		this.lastMessageText = lastMessageText;
	}

	public Instant getLastIncomingAt() {
		return lastIncomingAt;
	}

	public void setLastIncomingAt(Instant lastIncomingAt) {
		this.lastIncomingAt = lastIncomingAt;
	}

	public Instant getAwaitingReplySince() {
		return awaitingReplySince;
	}

	public void setAwaitingReplySince(Instant awaitingReplySince) {
		this.awaitingReplySince = awaitingReplySince;
	}

	public Set<Tag> getTags() {
		return tags;
	}

}
