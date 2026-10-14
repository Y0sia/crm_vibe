package com.vibe.crm.tags;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.auth.CurrentUser;
import com.vibe.crm.common.ApiException;
import com.vibe.crm.leads.Lead;
import com.vibe.crm.leads.LeadEventType;
import com.vibe.crm.leads.LeadHistory;
import com.vibe.crm.leads.LeadRepository;

@Service
@Transactional
public class TagService {

	private final TagRepository tags;

	private final LeadRepository leads;

	private final LeadHistory history;

	public TagService(TagRepository tags, LeadRepository leads, LeadHistory history) {
		this.tags = tags;
		this.leads = leads;
		this.history = history;
	}

	@Transactional(readOnly = true)
	public List<TagDto> list() {
		Map<Long, Long> counts = counts();
		return tags.findAllByOrderByIdAsc().stream().map(t -> TagDto.of(t, counts.getOrDefault(t.getId(), 0L))).toList();
	}

	@Transactional(readOnly = true)
	public Tag get(Long id) {
		return tags.findById(id).orElseThrow(() -> ApiException.notFound("Тег не найден"));
	}

	public TagDto create(String name, TagColor color) {
		if (tags.existsByNameIgnoreCase(name.trim())) {
			throw ApiException.conflict("Тег с таким названием уже есть");
		}
		return TagDto.of(tags.save(new Tag(name, color)), 0);
	}

	public TagDto update(Long id, String name, TagColor color) {
		Tag tag = get(id);
		if (tags.existsByNameIgnoreCaseAndIdNot(name.trim(), id)) {
			throw ApiException.conflict("Тег с таким названием уже есть");
		}
		tag.setName(name);
		tag.setColor(color);
		return TagDto.of(tag, counts().getOrDefault(id, 0L));
	}

	/** Тег снимается со всех лидов (с записью в историю), сами лиды остаются. */
	public void delete(Long id) {
		Tag tag = get(id);
		Long actorId = CurrentUser.get().id();
		for (Lead lead : leads.findByTagsContains(tag)) {
			lead.getTags().remove(tag);
			history.record(lead, actorId, LeadEventType.TAG_REMOVED, tag.getName(), null);
		}
		leads.flush();
		tags.delete(tag);
	}

	private Map<Long, Long> counts() {
		Map<Long, Long> counts = new HashMap<>();
		for (Object[] row : leads.countByTag()) {
			counts.put((Long) row[0], (Long) row[1]);
		}
		return counts;
	}

}
