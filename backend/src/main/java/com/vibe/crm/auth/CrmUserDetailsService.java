package com.vibe.crm.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.vibe.crm.users.User;
import com.vibe.crm.users.UserRepository;

@Service
public class CrmUserDetailsService implements UserDetailsService {

	private final UserRepository users;

	public CrmUserDetailsService(UserRepository users) {
		this.users = users;
	}

	@Override
	public UserDetails loadUserByUsername(String login) {
		return users.findByLogin(User.normalizeLogin(login))
			.map(CrmUserPrincipal::of)
			.orElseThrow(() -> new UsernameNotFoundException(login));
	}

}
