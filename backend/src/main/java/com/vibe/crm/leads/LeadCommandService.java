package com.vibe.crm.leads;

import java.util.Objects;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.auth.CurrentUser;
import com.vibe.crm.common.ApiException;
import com.vibe.crm.statuses.Status;
import com.vibe.crm.statuses.StatusService;
import com.vibe.crm.tags.Tag;
import com.vibe.crm.tags.TagService;
import com.vibe.crm.users.User;
import com.vibe.crm.users.UserService;

/** Изменения лида менеджером. Каждое реальное изменение пишется в историю. */
@Service
@Transactional
public class LeadCommandService {

	private final LeadQueryService queries;

	private final StatusService statuses;

	private final TagService tags;

	private final UserService users;

	private final LeadHistory history;

	private final ApplicationEventPublisher events;

	public LeadCommandService(LeadQueryService queries, StatusService statuses, TagService tags, UserService users,
			LeadHistory history, ApplicationEventPublisher events) {
		this.queries = queries;
		this.statuses = statuses;
		this.tags = tags;
		this.users = users;
		this.history = history;
		this.events = events;
	}

	public void changeStatus(Long leadId, Long statusId) {
		Lead lead = queries.get(leadId);
		Status status = statuses.get(statusId);
		if (lead.getStatus().getId().equals(status.getId())) {
			return;
		}
		String old = lead.getStatus().getName();
		lead.setStatus(status);
		changed(lead, LeadEventType.STATUS_CHANGED, old, status.getName());
	}

	public void assign(Long leadId, Long managerId) {
		Lead lead = queries.get(leadId);
		User manager = managerId == null ? null : users.get(managerId);
		if (manager != null && !manager.isActive()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Пользователь заблокирован");
		}
		Long oldId = lead.getManager() == null ? null : lead.getManager().getId();
		if (Objects.equals(oldId, managerId)) {
			return;
		}
		String old = lead.getManager() == null ? null : lead.getManager().getDisplayName();
		lead.setManager(manager);
		changed(lead, LeadEventType.MANAGER_CHANGED, old, manager == null ? null : manager.getDisplayName());
	}

	public void changeSource(Long leadId, LeadSource source) {
		Lead lead = queries.get(leadId);
		if (lead.getSource() == source) {
			return;
		}
		String old = LeadSources.label(lead.getSource());
		lead.setSource(source);
		changed(lead, LeadEventType.SOURCE_CHANGED, old, LeadSources.label(source));
	}

	public void addTag(Long leadId, Long tagId) {
		Lead lead = queries.get(leadId);
		Tag tag = tags.get(tagId);
		if (lead.getTags().add(tag)) {
			changed(lead, LeadEventType.TAG_ADDED, null, tag.getName());
		}
	}

	public void removeTag(Long leadId, Long tagId) {
		Lead lead = queries.get(leadId);
		Tag tag = tags.get(tagId);
		if (lead.getTags().remove(tag)) {
			changed(lead, LeadEventType.TAG_REMOVED, tag.getName(), null);
		}
	}

	private void changed(Lead lead, LeadEventType type, String oldValue, String newValue) {
		history.record(lead, CurrentUser.get().id(), type, oldValue, newValue);
		events.publishEvent(new LeadActivity(lead.getId(), LeadActivity.Kind.LEAD_UPDATED));
	}

}
