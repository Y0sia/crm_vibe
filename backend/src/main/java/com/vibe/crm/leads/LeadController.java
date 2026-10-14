package com.vibe.crm.leads;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.vibe.crm.common.ApiException;
import com.vibe.crm.leads.LeadViews.EventView;
import com.vibe.crm.leads.LeadViews.LeadCard;
import com.vibe.crm.leads.LeadViews.LeadPage;
import com.vibe.crm.leads.LeadViews.MessageView;
import com.vibe.crm.messages.ReplyService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/leads")
public class LeadController {

	public record StatusRequest(@NotNull(message = "Выберите статус") Long statusId) {
	}

	public record ManagerRequest(Long managerId) {
	}

	public record SourceRequest(LeadSource source) {
	}

	public record PhoneRequest(String phone) {
	}

	public record ReplyRequest(@NotBlank(message = "Введите сообщение") @Size(max = 4096,
			message = "Сообщение: до 4096 символов") String text) {
	}

	private final LeadQueryService queries;

	private final LeadCommandService commands;

	private final ReplyService replies;

	public LeadController(LeadQueryService queries, LeadCommandService commands, ReplyService replies) {
		this.queries = queries;
		this.commands = commands;
		this.replies = replies;
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

	@PutMapping("/{id}/status")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void status(@PathVariable Long id, @Valid @RequestBody StatusRequest body) {
		commands.changeStatus(id, body.statusId());
	}

	@PutMapping("/{id}/manager")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void manager(@PathVariable Long id, @RequestBody ManagerRequest body) {
		commands.assign(id, body.managerId());
	}

	@PutMapping("/{id}/source")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void source(@PathVariable Long id, @RequestBody SourceRequest body) {
		commands.changeSource(id, body.source());
	}

	@PutMapping("/{id}/phone")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void phone(@PathVariable Long id, @RequestBody PhoneRequest body) {
		commands.changePhone(id, body.phone());
	}

	@PostMapping("/{id}/tags/{tagId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void addTag(@PathVariable Long id, @PathVariable Long tagId) {
		commands.addTag(id, tagId);
	}

	@DeleteMapping("/{id}/tags/{tagId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void removeTag(@PathVariable Long id, @PathVariable Long tagId) {
		commands.removeTag(id, tagId);
	}

	@PostMapping("/{id}/messages")
	@ResponseStatus(HttpStatus.CREATED)
	public MessageView reply(@PathVariable Long id, @Valid @RequestBody ReplyRequest body) {
		return replies.reply(id, body.text());
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
