package com.vibe.crm.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.vibe.crm.common.ApiException;
import com.vibe.crm.users.Role;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	public record LoginRequest(@NotBlank(message = "Введите логин") String login,
			@NotBlank(message = "Введите пароль") String password) {
	}

	public record MeResponse(Long id, String login, String displayName, Role role) {

		static MeResponse of(CrmUserPrincipal p) {
			return new MeResponse(p.id(), p.login(), p.displayName(), p.role());
		}

	}

	private final AuthenticationManager authenticationManager;

	private final SecurityContextRepository securityContextRepository;

	private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

	public AuthController(AuthenticationManager authenticationManager,
			SecurityContextRepository securityContextRepository) {
		this.authenticationManager = authenticationManager;
		this.securityContextRepository = securityContextRepository;
	}

	@PostMapping("/login")
	public MeResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		Authentication auth;
		try {
			auth = authenticationManager
				.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(body.login(), body.password()));
		}
		catch (DisabledException e) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "Учётная запись заблокирована");
		}
		catch (BadCredentialsException e) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "Неверный логин или пароль");
		}
		catch (AuthenticationException e) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "Неверный логин или пароль");
		}
		// Защита от фиксации сессии: новый id сессии после входа.
		request.getSession(true);
		request.changeSessionId();
		CrmUserPrincipal principal = ((CrmUserPrincipal) auth.getPrincipal()).withoutPassword();
		SecurityContext context = holder.createEmptyContext();
		context.setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
		holder.setContext(context);
		securityContextRepository.saveContext(context, request, response);
		return MeResponse.of(principal);
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		holder.clearContext();
	}

	@GetMapping("/me")
	public MeResponse me() {
		return MeResponse.of(CurrentUser.get());
	}

}
