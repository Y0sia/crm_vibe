package com.vibe.crm.users;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

	public record CreateUserRequest(
			@NotBlank(message = "Введите логин") @Pattern(regexp = "^\\s*[A-Za-z0-9._-]{3,64}\\s*$",
					message = "Логин: 3–64 символа, латиница, цифры, точка, дефис, подчёркивание") String login,
			@NotNull(message = "Введите пароль") @Size(min = 8, max = 72,
					message = "Пароль: от 8 до 72 символов") String password,
			@NotBlank(message = "Введите имя") @Size(max = 100, message = "Имя: до 100 символов") String displayName,
			@NotNull(message = "Выберите роль") Role role) {
	}

	public record UpdateUserRequest(
			@NotBlank(message = "Введите имя") @Size(max = 100, message = "Имя: до 100 символов") String displayName,
			@NotNull(message = "Выберите роль") Role role) {
	}

	public record PasswordRequest(@NotNull(message = "Введите пароль") @Size(min = 8, max = 72,
			message = "Пароль: от 8 до 72 символов") String password) {
	}

	private final UserService service;

	public AdminUserController(UserService service) {
		this.service = service;
	}

	@GetMapping
	public List<UserDto> list() {
		return service.list();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public UserDto create(@Valid @RequestBody CreateUserRequest body) {
		return service.create(body.login(), body.password(), body.displayName(), body.role());
	}

	@PatchMapping("/{id}")
	public UserDto update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest body) {
		return service.update(id, body.displayName(), body.role());
	}

	@PutMapping("/{id}/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void changePassword(@PathVariable Long id, @Valid @RequestBody PasswordRequest body,
			HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		service.changePassword(id, body.password(), session == null ? null : session.getId());
	}

	@PostMapping("/{id}/block")
	public UserDto block(@PathVariable Long id) {
		return service.setActive(id, false);
	}

	@PostMapping("/{id}/unblock")
	public UserDto unblock(@PathVariable Long id) {
		return service.setActive(id, true);
	}

}
