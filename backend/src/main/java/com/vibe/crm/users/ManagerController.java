package com.vibe.crm.users;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class ManagerController {

	public record ManagerDto(Long id, String displayName) {
	}

	private final UserService service;

	public ManagerController(UserService service) {
		this.service = service;
	}

	@GetMapping("/managers")
	public List<ManagerDto> managers() {
		return service.activeUsers().stream().map(u -> new ManagerDto(u.getId(), u.getDisplayName())).toList();
	}

}
