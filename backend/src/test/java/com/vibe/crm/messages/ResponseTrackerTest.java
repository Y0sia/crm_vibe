package com.vibe.crm.messages;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.IntegrationTest;
import com.vibe.crm.TestData;
import com.vibe.crm.leads.Lead;
import com.vibe.crm.users.Role;
import com.vibe.crm.users.User;

@IntegrationTest
@Transactional
class ResponseTrackerTest {

	@Autowired
	TestData data;

	@Autowired
	ResponseTracker tracker;

	@Autowired
	ResponseEventRepository responses;

	@BeforeEach
	void clean() {
		data.resetStatusesAndTags();
	}

	@Test
	void seriesOfIncomingThenReplyIsOneResponse() {
		Lead lead = data.lead(1);
		User maria = data.user("rt-maria", "maria-pass", Role.MANAGER);
		Instant t0 = Instant.parse("2026-10-13T12:47:00Z");

		tracker.onIncoming(lead, t0);
		tracker.onIncoming(lead, t0.plusSeconds(60));
		tracker.onOutgoing(lead, t0.plusSeconds(15 * 60), maria.getId());
		tracker.onOutgoing(lead, t0.plusSeconds(16 * 60), maria.getId());

		assertThat(responses.findByLeadOrderByAnsweredAtAsc(lead)).singleElement().satisfies(r -> {
			assertThat(r.getWaitedSeconds()).isEqualTo(15 * 60);
			assertThat(r.getAuthor().getId()).isEqualTo(maria.getId());
		});
		assertThat(lead.getAwaitingReplySince()).isNull();
	}

	@Test
	void eachReplyAfterNewIncomingIsCounted() {
		Lead lead = data.lead(2);
		Instant t0 = Instant.parse("2026-10-13T12:00:00Z");

		tracker.onIncoming(lead, t0);
		tracker.onOutgoing(lead, t0.plusSeconds(30), null);
		tracker.onIncoming(lead, t0.plusSeconds(100));
		tracker.onOutgoing(lead, t0.plusSeconds(400), null);

		assertThat(responses.findByLeadOrderByAnsweredAtAsc(lead)).extracting(ResponseEvent::getWaitedSeconds)
			.containsExactly(30L, 300L);
	}

	@Test
	void outgoingWithoutWaitingIsNotAResponse() {
		Lead lead = data.lead(3);
		tracker.onOutgoing(lead, Instant.now(), null);
		assertThat(responses.findByLeadOrderByAnsweredAtAsc(lead)).isEmpty();
	}

}
