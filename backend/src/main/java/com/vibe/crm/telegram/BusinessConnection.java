package com.vibe.crm.telegram;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Подключение бота к бизнес-аккаунту. owner — сам аккаунт компании. */
@Entity
@Table(name = "business_connections")
public class BusinessConnection {

	@Id
	@Column(length = 128)
	private String id;

	@Column(name = "owner_telegram_user_id", nullable = false)
	private long ownerTelegramUserId;

	@Column(name = "user_chat_id", nullable = false)
	private long userChatId;

	@Column(name = "can_reply", nullable = false)
	private boolean canReply;

	@Column(nullable = false)
	private boolean enabled;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected BusinessConnection() {
	}

	public BusinessConnection(String id) {
		this.id = id;
	}

	public String getId() {
		return id;
	}

	public long getOwnerTelegramUserId() {
		return ownerTelegramUserId;
	}

	public void setOwnerTelegramUserId(long ownerTelegramUserId) {
		this.ownerTelegramUserId = ownerTelegramUserId;
	}

	public long getUserChatId() {
		return userChatId;
	}

	public void setUserChatId(long userChatId) {
		this.userChatId = userChatId;
	}

	public boolean isCanReply() {
		return canReply;
	}

	public void setCanReply(boolean canReply) {
		this.canReply = canReply;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}

}
