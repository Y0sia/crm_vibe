package com.vibe.crm.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.vibe.crm.IntegrationTest;
import com.vibe.crm.TestData;
import com.vibe.crm.leads.Lead;
import com.vibe.crm.leads.LeadEventRepository;
import com.vibe.crm.leads.LeadEventType;
import com.vibe.crm.leads.LeadEvent;
import com.vibe.crm.leads.LeadRepository;
import com.vibe.crm.messages.Direction;
import com.vibe.crm.messages.Message;
import com.vibe.crm.messages.MessageRepository;
import com.vibe.crm.messages.ResponseEventRepository;
import com.vibe.crm.telegram.api.TgUpdate;

import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class UpdateProcessorTest {

	static final long OWNER = 777000111L;

	static final long LEAD = 548213097L;

	@Autowired
	UpdateProcessor processor;

	@Autowired
	FakeTelegramBotClient telegram;

	@Autowired
	TestData data;

	@Autowired
	LeadRepository leads;

	@Autowired
	MessageRepository messages;

	@Autowired
	LeadEventRepository events;

	@Autowired
	ResponseEventRepository responses;

	@Autowired
	BusinessConnectionRepository connections;

	@Autowired
	TransactionTemplate tx;

	JsonMapper mapper = JsonMapper.builder().build();

	@BeforeEach
	void setUp() {
		data.resetStatusesAndTags();
		data.clearTelegram();
		telegram.reset();
		process("""
				{"update_id":1,"business_connection":{"id":"bc1","user":{"id":%d,"is_bot":false,"first_name":"Компания"},
				 "user_chat_id":%d,"date":1760350000,"rights":{"can_reply":true},"is_enabled":true}}
				""".formatted(OWNER, OWNER));
	}

	void process(String json) {
		processor.process(mapper.readValue(json, TgUpdate.class));
	}

	String incoming(long updateId, long messageId, long date, String content) {
		return """
				{"update_id":%d,"business_message":{"message_id":%d,"business_connection_id":"bc1","date":%d,
				 "from":{"id":%d,"is_bot":false,"first_name":"Дмитрий","last_name":"Орлов","username":"orlov_d"},
				 "chat":{"id":%d,"type":"private","first_name":"Дмитрий"},%s}}
				""".formatted(updateId, messageId, date, LEAD, LEAD, content);
	}

	String outgoingFromApp(long updateId, long messageId, long date, String text) {
		return """
				{"update_id":%d,"business_message":{"message_id":%d,"business_connection_id":"bc1","date":%d,
				 "from":{"id":%d,"is_bot":false,"first_name":"Компания"},
				 "chat":{"id":%d,"type":"private","first_name":"Дмитрий"},"text":"%s"}}
				""".formatted(updateId, messageId, date, OWNER, LEAD, text);
	}

	<T> T inTx(java.util.function.Supplier<T> s) {
		return tx.execute(t -> s.get());
	}

	@Test
	void connectionIsStored() {
		BusinessConnection c = connections.findById("bc1").orElseThrow();
		assertThat(c.getOwnerTelegramUserId()).isEqualTo(OWNER);
		assertThat(c.isCanReply()).isTrue();
		assertThat(c.isEnabled()).isTrue();
	}

	@Test
	void firstIncomingMessageCreatesLead() {
		process(incoming(2, 15, 1760359620, "\"text\":\"Нужна партия на 120 штук\""));

		inTx(() -> {
			Lead lead = leads.findByTelegramUserId(LEAD).orElseThrow();
			assertThat(lead.displayName()).isEqualTo("Дмитрий Орлов");
			assertThat(lead.getUsername()).isEqualTo("orlov_d");
			assertThat(lead.getStatus().getName()).isEqualTo("Новый");
			assertThat(lead.getManager()).isNull();
			assertThat(lead.getSource()).isNull();
			assertThat(lead.getBusinessConnectionId()).isEqualTo("bc1");
			assertThat(lead.getLastMessageText()).isEqualTo("Нужна партия на 120 штук");
			assertThat(lead.getLastIncomingAt().getEpochSecond()).isEqualTo(1760359620L);
			assertThat(lead.getAwaitingReplySince().getEpochSecond()).isEqualTo(1760359620L);
			assertThat(events.findByLeadOrderByCreatedAtDescIdDesc(lead)).extracting(LeadEvent::getType)
				.containsExactly(LeadEventType.CREATED);
			assertThat(messages.findByLeadOrderBySentAtAscIdAsc(lead)).singleElement().satisfies(m -> {
				assertThat(m.getDirection()).isEqualTo(Direction.IN);
				assertThat(m.getTelegramMessageId()).isEqualTo(15);
			});
			return null;
		});
	}

	@Test
	void secondMessageDoesNotCreateNewLeadAndDuplicatesAreIgnored() {
		process(incoming(2, 15, 1760359620, "\"text\":\"Первое\""));
		process(incoming(3, 16, 1760359680, "\"text\":\"Второе\""));
		process(incoming(3, 16, 1760359680, "\"text\":\"Второе\""));

		assertThat(leads.count()).isEqualTo(1);
		inTx(() -> {
			Lead lead = leads.findByTelegramUserId(LEAD).orElseThrow();
			assertThat(messages.findByLeadOrderBySentAtAscIdAsc(lead)).extracting(Message::getText)
				.containsExactly("Первое", "Второе");
			assertThat(lead.getLastMessageText()).isEqualTo("Второе");
			return null;
		});
	}

	@Test
	void replyFromTelegramAppIsStoredAndCountedWithoutAuthor() {
		process(incoming(2, 15, 1760359620, "\"text\":\"Сроки?\""));
		process(outgoingFromApp(3, 16, 1760360520, "5–7 рабочих дней"));

		inTx(() -> {
			Lead lead = leads.findByTelegramUserId(LEAD).orElseThrow();
			List<Message> all = messages.findByLeadOrderBySentAtAscIdAsc(lead);
			assertThat(all).extracting(Message::getDirection).containsExactly(Direction.IN, Direction.OUT);
			assertThat(all.get(1).getAuthor()).isNull();
			assertThat(responses.findByLeadOrderByAnsweredAtAsc(lead)).singleElement().satisfies(r -> {
				assertThat(r.getWaitedSeconds()).isEqualTo(900);
				assertThat(r.getAuthor()).isNull();
			});
			assertThat(lead.getAwaitingReplySince()).isNull();
			return null;
		});
	}

	@Test
	void ownerWritingFirstDoesNotCreateLead() {
		process(outgoingFromApp(2, 15, 1760359620, "Здравствуйте, это компания"));
		assertThat(leads.count()).isZero();
	}

	@Test
	void messagesSentByOurBotAreSkipped() {
		process(incoming(2, 15, 1760359620, "\"text\":\"Сроки?\""));
		process("""
				{"update_id":3,"business_message":{"message_id":16,"business_connection_id":"bc1","date":1760360520,
				 "from":{"id":%d,"is_bot":false,"first_name":"Компания"},
				 "sender_business_bot":{"id":8614677825,"is_bot":true,"first_name":"CRM секретарь"},
				 "chat":{"id":%d,"type":"private"},"text":"Ответ из CRM"}}
				""".formatted(OWNER, LEAD));

		inTx(() -> {
			Lead lead = leads.findByTelegramUserId(LEAD).orElseThrow();
			assertThat(messages.findByLeadOrderBySentAtAscIdAsc(lead)).hasSize(1);
			assertThat(responses.findByLeadOrderByAnsweredAtAsc(lead)).isEmpty();
			return null;
		});
	}

	@Test
	void mediaIsStoredAsPlaceholderAndOwnContactSetsPhone() {
		process(incoming(2, 15, 1760359620, "\"photo\":[{\"file_id\":\"x\"}],\"caption\":\"Вот так\""));
		process(incoming(3, 16, 1760359680,
				"\"contact\":{\"phone_number\":\"79000000000\",\"first_name\":\"Дмитрий\",\"user_id\":%d}".formatted(LEAD)));

		inTx(() -> {
			Lead lead = leads.findByTelegramUserId(LEAD).orElseThrow();
			assertThat(messages.findByLeadOrderBySentAtAscIdAsc(lead)).extracting(Message::getText)
				.containsExactly("[Фото] Вот так", "[Контакт]");
			assertThat(lead.getPhone()).isEqualTo("+79000000000");
			return null;
		});
	}

	@Test
	void foreignContactDoesNotSetPhone() {
		process(incoming(2, 15, 1760359620,
				"\"contact\":{\"phone_number\":\"+71111111111\",\"first_name\":\"Друг\",\"user_id\":42}"));
		inTx(() -> {
			assertThat(leads.findByTelegramUserId(LEAD).orElseThrow().getPhone()).isNull();
			return null;
		});
	}

	@Test
	void editAndDelete() {
		process(incoming(2, 15, 1760359620, "\"text\":\"Нужно 100 штук\""));
		process("""
				{"update_id":3,"edited_business_message":{"message_id":15,"business_connection_id":"bc1","date":1760359620,
				 "edit_date":1760359700,"from":{"id":%d,"is_bot":false,"first_name":"Дмитрий"},
				 "chat":{"id":%d,"type":"private"},"text":"Нужно 120 штук"}}
				""".formatted(LEAD, LEAD));

		inTx(() -> {
			Lead lead = leads.findByTelegramUserId(LEAD).orElseThrow();
			Message m = messages.findByLeadAndTelegramMessageId(lead, 15).orElseThrow();
			assertThat(m.getText()).isEqualTo("Нужно 120 штук");
			assertThat(m.getEditedAt().getEpochSecond()).isEqualTo(1760359700L);
			assertThat(lead.getLastMessageText()).isEqualTo("Нужно 120 штук");
			return null;
		});

		process("""
				{"update_id":4,"deleted_business_messages":{"business_connection_id":"bc1",
				 "chat":{"id":%d,"type":"private"},"message_ids":[15]}}
				""".formatted(LEAD));

		inTx(() -> {
			Lead lead = leads.findByTelegramUserId(LEAD).orElseThrow();
			assertThat(messages.findByLeadAndTelegramMessageId(lead, 15).orElseThrow().isDeleted()).isTrue();
			return null;
		});
	}

	@Test
	void groupMessagesAreIgnored() {
		process("""
				{"update_id":2,"business_message":{"message_id":15,"business_connection_id":"bc1","date":1760359620,
				 "from":{"id":%d,"is_bot":false,"first_name":"Дмитрий"},
				 "chat":{"id":-100123,"type":"supergroup"},"text":"Привет"}}
				""".formatted(LEAD));
		assertThat(leads.count()).isZero();
	}

	@Test
	void unknownConnectionIsFetchedFromTelegram() {
		telegram.connections.put("bc2", mapper.readValue("""
				{"id":"bc2","user":{"id":%d,"is_bot":false,"first_name":"Компания"},"user_chat_id":%d,
				 "date":1760350000,"rights":{"can_reply":false},"is_enabled":true}
				""".formatted(OWNER, OWNER), com.vibe.crm.telegram.api.TgBusinessConnection.class));

		process("""
				{"update_id":2,"business_message":{"message_id":15,"business_connection_id":"bc2","date":1760359620,
				 "from":{"id":%d,"is_bot":false,"first_name":"Дмитрий"},
				 "chat":{"id":%d,"type":"private"},"text":"Привет"}}
				""".formatted(LEAD, LEAD));

		assertThat(connections.findById("bc2").orElseThrow().isCanReply()).isFalse();
		assertThat(leads.count()).isEqualTo(1);
	}

	@Test
	void disconnectIsStored() {
		process("""
				{"update_id":5,"business_connection":{"id":"bc1","user":{"id":%d,"is_bot":false,"first_name":"Компания"},
				 "user_chat_id":%d,"date":1760350000,"is_enabled":false}}
				""".formatted(OWNER, OWNER));
		BusinessConnection c = connections.findById("bc1").orElseThrow();
		assertThat(c.isEnabled()).isFalse();
		assertThat(c.isCanReply()).isFalse();
	}
}
