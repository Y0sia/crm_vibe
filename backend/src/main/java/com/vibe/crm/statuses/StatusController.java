package com.vibe.crm.statuses;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/statuses")
public class StatusController {

	public record StatusRequest(
			@NotBlank(message = "Введите название") @Size(max = 64, message = "Название: до 64 символов") String name,
			@NotNull(message = "Выберите цвет") StatusColor color) {
	}

	public record OrderRequest(@NotEmpty(message = "Передайте все статусы в новом порядке") List<Long> ids) {
	}

	private final StatusService service;

	public StatusController(StatusService service) {
		this.service = service;
	}

	@GetMapping
	public List<StatusDto> list() {
		return service.list();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasRole('ADMIN')")
	public StatusDto create(@Valid @RequestBody StatusRequest body) {
		return service.create(body.name(), body.color());
	}

	@PatchMapping("/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public StatusDto update(@PathVariable Long id, @Valid @RequestBody StatusRequest body) {
		return service.update(id, body.name(), body.color());
	}

	@PutMapping("/order")
	@PreAuthorize("hasRole('ADMIN')")
	public List<StatusDto> reorder(@Valid @RequestBody OrderRequest body) {
		return service.reorder(body.ids());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasRole('ADMIN')")
	public void delete(@PathVariable Long id) {
		service.delete(id);
	}

}
