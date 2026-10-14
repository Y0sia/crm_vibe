package com.vibe.crm.messages;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.leads.Lead;
import com.vibe.crm.users.UserRepository;

/**
 * Время ответа: серия входящих сообщений — одно ожидание, первый исходящий после неё — один ответ.
 */
@Service
@Transactional
public class ResponseTracker {

	private final ResponseEventRepository responses;

	private final UserRepository users;

	public ResponseTracker(ResponseEventRepository responses, UserRepository users) {
		this.responses = responses;
		this.users = users;
	}

	public void onIncoming(Lead lead, Instant at) {
		if (lead.getAwaitingReplySince() == null) {
			lead.setAwaitingReplySince(at);
		}
	}

	public void onOutgoing(Lead lead, Instant at, Long authorId) {
		Instant since = lead.getAwaitingReplySince();
		if (since == null) {
			return;
		}
		long waited = Math.max(0, Duration.between(since, at).toSeconds());
		responses.save(new ResponseEvent(lead, authorId == null ? null : users.getReferenceById(authorId), waited, at));
		lead.setAwaitingReplySince(null);
	}

}
