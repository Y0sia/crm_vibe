package com.vibe.crm.leads;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vibe.crm.messages.Direction;
import com.vibe.crm.messages.Message;
import com.vibe.crm.tags.Tag;
import com.vibe.crm.tags.TagColor;
import com.vibe.crm.users.User;

/** Ответы API лидов. */
public final class LeadViews {

	private LeadViews() {
	}

	public record UserRef(Long id, String displayName) {

		static UserRef of(User u) {
			return u == null ? null : new UserRef(u.getId(), u.getDisplayName());
		}

	}

	public record TagRef(Long id, String name, TagColor color) {

		static List<TagRef> of(Lead lead) {
			return lead.getTags().stream().sorted(Comparator.comparing(Tag::getId))
				.map(t -> new TagRef(t.getId(), t.getName(), t.getColor())).toList();
		}

	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LeadListItem(Long id, String name, String username, Long statusId, List<TagRef> tags,
			UserRef manager, LeadSource source, String lastMessageText, Instant lastMessageAt) {

		static LeadListItem of(Lead l) {
			return new LeadListItem(l.getId(), l.displayName(), l.getUsername(), l.getStatus().getId(), TagRef.of(l),
					UserRef.of(l.getManager()), l.getSource(), l.getLastMessageText(), l.getLastMessageAt());
		}

	}

	public record LeadPage(List<LeadListItem> items, int page, int size, long found, long total,
			Map<Long, Long> statusCounts) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record LeadCard(Long id, String name, String firstName, String lastName, String username,
			long telegramUserId, String phone, Long statusId, List<TagRef> tags, UserRef manager, LeadSource source,
			Instant createdAt, Instant lastMessageAt, boolean canReply, String replyBlockedReason, String telegramLink) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record MessageView(Long id, Direction direction, String text, Instant sentAt, UserRef author,
			boolean edited, boolean deleted) {

		public static MessageView of(Message m) {
			return new MessageView(m.getId(), m.getDirection(), m.getText(), m.getSentAt(), UserRef.of(m.getAuthor()),
					m.getEditedAt() != null, m.isDeleted());
		}

	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record EventView(Long id, LeadEventType type, String oldValue, String newValue, UserRef actor,
			Instant createdAt) {

		static EventView of(LeadEvent e) {
			return new EventView(e.getId(), e.getType(), e.getOldValue(), e.getNewValue(), UserRef.of(e.getActor()),
					e.getCreatedAt());
		}

	}

}
