package com.vibe.crm.leads;

import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.common.ApiException;
import com.vibe.crm.leads.LeadViews.EventView;
import com.vibe.crm.leads.LeadViews.LeadCard;
import com.vibe.crm.leads.LeadViews.LeadListItem;
import com.vibe.crm.leads.LeadViews.LeadPage;
import com.vibe.crm.leads.LeadViews.MessageView;
import com.vibe.crm.leads.LeadViews.TagRef;
import com.vibe.crm.leads.LeadViews.UserRef;
import com.vibe.crm.messages.MessageRepository;
import com.vibe.crm.messages.ReplyAvailability;
import com.vibe.crm.statuses.StatusService;
import com.vibe.crm.telegram.BusinessConnection;
import com.vibe.crm.telegram.BusinessConnectionRepository;

@Service
@Transactional(readOnly = true)
public class LeadQueryService {

	private static final Sort ORDER = Sort.by(Sort.Order.desc("lastMessageAt").nullsLast(), Sort.Order.desc("id"));

	private final LeadRepository leads;

	private final MessageRepository messages;

	private final LeadEventRepository events;

	private final BusinessConnectionRepository connections;

	private final StatusService statuses;

	private final ZoneId zone;

	public LeadQueryService(LeadRepository leads, MessageRepository messages, LeadEventRepository events,
			BusinessConnectionRepository connections, StatusService statuses,
			@Value("${crm.timezone:Europe/Moscow}") String timezone) {
		this.leads = leads;
		this.messages = messages;
		this.events = events;
		this.connections = connections;
		this.statuses = statuses;
		this.zone = ZoneId.of(timezone);
	}

	public Lead get(Long id) {
		return leads.findById(id).orElseThrow(() -> ApiException.notFound("Лид не найден"));
	}

	public LeadPage list(LeadFilter filter, int page, int size) {
		Page<Lead> result = leads.findAll(LeadSpecifications.of(filter, zone), PageRequest.of(page, size, ORDER));
		Map<Long, Long> counts = new HashMap<>();
		for (Object[] row : leads.countByStatus()) {
			counts.put((Long) row[0], (Long) row[1]);
		}
		return new LeadPage(result.getContent().stream().map(LeadListItem::of).toList(), page, size,
				result.getTotalElements(), leads.count(), counts);
	}

	public LeadCard card(Long id) {
		Lead l = get(id);
		BusinessConnection connection = l.getBusinessConnectionId() == null ? null
				: connections.findById(l.getBusinessConnectionId()).orElse(null);
		String reason = ReplyAvailability.reason(l, connection, Instant.now());
		String link = l.getUsername() != null && !l.getUsername().isBlank() ? "https://t.me/" + l.getUsername()
				: "tg://user?id=" + l.getTelegramUserId();
		return new LeadCard(l.getId(), l.displayName(), l.getFirstName(), l.getLastName(), l.getUsername(),
				l.getTelegramUserId(), l.getPhone(), l.getStatus().getId(), TagRef.of(l), UserRef.of(l.getManager()),
				l.getSource(), l.getCreatedAt(), l.getLastMessageAt(), reason == null, reason, link);
	}

	public List<MessageView> messages(Long id) {
		return messages.findByLeadOrderBySentAtAscIdAsc(get(id)).stream().map(MessageView::of).toList();
	}

	public List<EventView> events(Long id) {
		return events.findByLeadOrderByCreatedAtDescIdDesc(get(id)).stream().map(EventView::of).toList();
	}

	public long newCount() {
		return leads.countByStatus(statuses.first());
	}

}
