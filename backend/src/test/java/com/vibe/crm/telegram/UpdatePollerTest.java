package com.vibe.crm.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.vibe.crm.IntegrationTest;
import com.vibe.crm.TestData;
import com.vibe.crm.leads.LeadRepository;
import com.vibe.crm.telegram.api.TgUpdate;

import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class UpdatePollerTest {

	@Autowired
	UpdatePoller poller;

	@Autowired
	FakeTelegramBotClient telegram;

	@Autowired
	TelegramStateRepository state;

	@Autowired
	LeadRepository leads;

	@Autowired
	TestData data;

	JsonMapper mapper = JsonMapper.builder().build();

	@BeforeEach
	void setUp() {
		data.resetStatusesAndTags();
		data.clearTelegram();
		telegram.reset();
	}

	TgUpdate update(String json) {
		return mapper.readValue(json, TgUpdate.class);
	}

	@Test
	void pollingIsNotRunningInTests() {
		assertThat(poller.isRunning()).isFalse();
	}

	@Test
	void processesBatchAndStoresOffset() {
		telegram.queue.add(update("""
				{"update_id":41,"business_connection":{"id":"bc1","user":{"id":1,"is_bot":false,"first_name":"К"},
				 "user_chat_id":1,"date":1,"rights":{"can_reply":true},"is_enabled":true}}
				"""));
		telegram.queue.add(update("""
				{"update_id":42,"business_message":{"message_id":5,"business_connection_id":"bc1","date":1760359620,
				 "from":{"id":2,"is_bot":false,"first_name":"Анна"},"chat":{"id":2,"type":"private"},"text":"Привет"}}
				"""));

		assertThat(poller.pollOnce()).isEqualTo(2);
		assertThat(state.findById(TelegramState.OFFSET).orElseThrow().getValue()).isEqualTo("43");
		assertThat(leads.count()).isEqualTo(1);

		poller.pollOnce();
		assertThat(telegram.offsets).containsExactly(0L, 43L);
	}

	@Test
	void brokenUpdateDoesNotStopTheBatch() {
		telegram.queue.add(update("""
				{"update_id":50,"business_message":{"message_id":5,"business_connection_id":"unknown","date":1,
				 "from":{"id":2,"is_bot":false,"first_name":"Анна"},"chat":{"id":2,"type":"private"},"text":"x"}}
				"""));
		telegram.queue.add(update("""
				{"update_id":51,"business_connection":{"id":"bc1","user":{"id":1,"is_bot":false,"first_name":"К"},
				 "user_chat_id":1,"date":1,"rights":{"can_reply":true},"is_enabled":true}}
				"""));

		assertThat(poller.pollOnce()).isEqualTo(2);
		assertThat(state.findById(TelegramState.OFFSET).orElseThrow().getValue()).isEqualTo("52");
	}

}
