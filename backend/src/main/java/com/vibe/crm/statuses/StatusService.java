package com.vibe.crm.statuses;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
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
public class StatusService {

	private final StatusRepository statuses;

	private final LeadRepository leads;

	private final LeadHistory history;

	public StatusService(StatusRepository statuses, LeadRepository leads, LeadHistory history) {
		this.statuses = statuses;
		this.leads = leads;
		this.history = history;
	}

	@Transactional(readOnly = true)
	public List<StatusDto> list() {
		Map<Long, Long> counts = new HashMap<>();
		for (Object[] row : leads.countByStatus()) {
			counts.put((Long) row[0], (Long) row[1]);
		}
		return statuses.findAllByOrderByPositionAsc().stream()
			.map(s -> StatusDto.of(s, counts.getOrDefault(s.getId(), 0L)))
			.toList();
	}

	@Transactional(readOnly = true)
	public Status get(Long id) {
		return statuses.findById(id).orElseThrow(() -> ApiException.notFound("Статус не найден"));
	}

	/** Первый этап воронки — его получают новые лиды. */
	@Transactional(readOnly = true)
	public Status first() {
		return statuses.findFirstByOrderByPositionAsc()
			.orElseThrow(() -> new IllegalStateException("В системе нет ни одного статуса"));
	}

	public StatusDto create(String name, StatusColor color) {
		if (statuses.existsByNameIgnoreCase(name.trim())) {
			throw ApiException.conflict("Статус с таким названием уже есть");
		}
		int position = (int) statuses.count();
		return StatusDto.of(statuses.save(new Status(name, color, position)), 0);
	}

	public StatusDto update(Long id, String name, StatusColor color) {
		Status status = get(id);
		if (statuses.existsByNameIgnoreCaseAndIdNot(name.trim(), id)) {
			throw ApiException.conflict("Статус с таким названием уже есть");
		}
		status.setName(name);
		status.setColor(color);
		return StatusDto.of(status, leads.countByStatus(status));
	}

	public List<StatusDto> reorder(List<Long> ids) {
		List<Status> all = statuses.findAllByOrderByPositionAsc();
		if (ids.size() != all.size() || !new HashSet<>(ids).equals(new HashSet<>(all.stream().map(Status::getId).toList()))) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Передайте все статусы в новом порядке");
		}
		Map<Long, Status> byId = new HashMap<>();
		all.forEach(s -> byId.put(s.getId(), s));
		for (int i = 0; i < ids.size(); i++) {
			byId.get(ids.get(i)).setPosition(i);
		}
		statuses.flush();
		return list();
	}

	/** Лиды удаляемого статуса переходят в первый из оставшихся; позиции перенумеровываются. */
	public void delete(Long id) {
		Status status = get(id);
		List<Status> rest = statuses.findAllByOrderByPositionAsc().stream().filter(s -> !s.getId().equals(id)).toList();
		if (rest.isEmpty()) {
			throw ApiException.conflict("Нельзя удалить единственный статус");
		}
		Status fallback = rest.getFirst();
		Long actorId = CurrentUser.get().id();
		for (Lead lead : leads.findByStatus(status)) {
			lead.setStatus(fallback);
			history.record(lead, actorId, LeadEventType.STATUS_CHANGED, status.getName(), fallback.getName());
		}
		leads.flush();
		statuses.delete(status);
		for (int i = 0; i < rest.size(); i++) {
			rest.get(i).setPosition(i);
		}
	}

}
