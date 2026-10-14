package com.vibe.crm.leads;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vibe.crm.common.ApiException;
import com.vibe.crm.leads.LeadViews.EventView;
import com.vibe.crm.leads.LeadViews.LeadCard;
import com.vibe.crm.leads.LeadViews.LeadPage;
import com.vibe.crm.leads.LeadViews.MessageView;

@RestController
@RequestMapping("/api/leads")
public class LeadController {

	private final LeadQueryService queries;

	public LeadController(LeadQueryService queries) {
		this.queries = queries;
	}

	@GetMapping
	public LeadPage list(@RequestParam(required = false) String q, @RequestParam(required = false) String messageText,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String manager, @RequestParam(required = false) String source,
			@RequestParam(required = false) List<Long> statusIds, @RequestParam(required = false) List<Long> tagIds,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
		boolean managerNone = "none".equals(manager);
		Long managerId = manager == null || managerNone || manager.isBlank() ? null : parseId(manager);
		boolean sourceNone = "none".equals(source);
		LeadSource src = source == null || sourceNone || source.isBlank() ? null : parseSource(source);
		LeadFilter filter = new LeadFilter(q, messageText, from, to, managerId, managerNone, src, sourceNone, statusIds,
				tagIds);
		return queries.list(filter, Math.max(page, 0), Math.clamp(size, 1, 100));
	}

	@GetMapping("/new-count")
	public Map<String, Long> newCount() {
		return Map.of("count", queries.newCount());
	}

	@GetMapping("/{id}")
	public LeadCard card(@PathVariable Long id) {
		return queries.card(id);
	}

	@GetMapping("/{id}/messages")
	public List<MessageView> messages(@PathVariable Long id) {
		return queries.messages(id);
	}

	@GetMapping("/{id}/events")
	public List<EventView> events(@PathVariable Long id) {
		return queries.events(id);
	}

	private static Long parseId(String value) {
		try {
			return Long.valueOf(value);
		}
		catch (NumberFormatException e) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Некорректный менеджер");
		}
	}

	private static LeadSource parseSource(String value) {
		try {
			return LeadSource.valueOf(value);
		}
		catch (IllegalArgumentException e) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Некорректный источник");
		}
	}

}
