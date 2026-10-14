package com.vibe.crm.telegram;

import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.leads.Lead;
import com.vibe.crm.leads.LeadActivity;
import com.vibe.crm.leads.LeadEventType;
import com.vibe.crm.leads.LeadHistory;
import com.vibe.crm.leads.LeadRepository;
import com.vibe.crm.messages.Direction;
import com.vibe.crm.messages.Message;
import com.vibe.crm.messages.MessageRepository;
import com.vibe.crm.messages.ResponseTracker;
import com.vibe.crm.statuses.StatusService;
import com.vibe.crm.telegram.api.TgBusinessConnection;
import com.vibe.crm.telegram.api.TgBusinessMessagesDeleted;
import com.vibe.crm.telegram.api.TgMessage;
import com.vibe.crm.telegram.api.TgUpdate;
import com.vibe.crm.telegram.api.TgUser;

/** Превращает апдейты Telegram Business в лидов и переписку. Один апдейт — одна транзакция. */
@Service
public class UpdateProcessor {

	private static final Logger log = LoggerFactory.getLogger(UpdateProcessor.class);

	private final BusinessConnectionRepository connections;

	private final LeadRepository leads;

	private final MessageRepository messages;

	private final StatusService statuses;

	private final LeadHistory history;

	private final ResponseTracker responses;

	private final TelegramBotClient telegram;

	private final ApplicationEventPublisher events;

	public UpdateProcessor(BusinessConnectionRepository connections, LeadRepository leads, MessageRepository messages,
			StatusService statuses, LeadHistory history, ResponseTracker responses, TelegramBotClient telegram,
			ApplicationEventPublisher events) {
		this.connections = connections;
		this.leads = leads;
		this.messages = messages;
		this.statuses = statuses;
		this.history = history;
		this.responses = responses;
		this.telegram = telegram;
		this.events = events;
	}

	@Transactional
	public void process(TgUpdate update) {
		if (update.businessConnection() != null) {
			saveConnection(update.businessConnection());
		}
		if (update.businessMessage() != null) {
			onMessage(update.businessMessage());
		}
		if (update.editedBusinessMessage() != null) {
			onEdit(update.editedBusinessMessage());
		}
		if (update.deletedBusinessMessages() != null) {
			onDelete(update.deletedBusinessMessages());
		}
	}

	private BusinessConnection saveConnection(TgBusinessConnection c) {
		BusinessConnection entity = connections.findById(c.id()).orElseGet(() -> new BusinessConnection(c.id()));
		entity.setOwnerTelegramUserId(c.user().id());
		entity.setUserChatId(c.userChatId());
		entity.setCanReply(c.canReply());
		entity.setEnabled(c.isEnabled());
		entity.setUpdatedAt(Instant.now());
		return connections.save(entity);
	}

	private Optional<BusinessConnection> connection(String id) {
		if (id == null) {
			return Optional.empty();
		}
		return connections.findById(id).or(() -> {
			try {
				return Optional.of(saveConnection(telegram.getBusinessConnection(id)));
			}
			catch (TelegramApiException e) {
				log.warn("Не удалось получить подключение {}: {}", id, e.getMessage());
				return Optional.empty();
			}
		});
	}

	private void onMessage(TgMessage m) {
		if (m.chat() == null || !"private".equals(m.chat().type()) || m.from() == null) {
			return;
		}
		if (m.senderBusinessBot() != null) {
			return; // отправлено из CRM — сохраняется в момент отправки
		}
		Optional<BusinessConnection> connection = connection(m.businessConnectionId());
		if (connection.isEmpty()) {
			log.warn("Сообщение {} без известного подключения {} — пропущено", m.messageId(), m.businessConnectionId());
			return;
		}
		boolean outgoing = m.from().id() == connection.get().getOwnerTelegramUserId();
		if (outgoing) {
			onOutgoingFromApp(m);
		}
		else {
			onIncoming(m);
		}
	}

	private void onIncoming(TgMessage m) {
		Instant at = m.sentAt();
		TgUser from = m.from();
		Lead lead = leads.findByTelegramUserId(from.id()).orElse(null);
		boolean created = lead == null;
		if (created) {
			lead = leads.save(new Lead(from.id(), m.chat().id(), statuses.first(), at));
			history.record(lead, null, LeadEventType.CREATED, null, null);
		}
		else if (messages.existsByLeadAndTelegramMessageId(lead, m.messageId())) {
			return;
		}
		lead.setTelegramChatId(m.chat().id());
		lead.setBusinessConnectionId(m.businessConnectionId());
		lead.setFirstName(from.firstName());
		lead.setLastName(from.lastName());
		lead.setUsername(from.username());
		if (m.contact() != null && m.contact().userId() != null && m.contact().userId() == from.id()) {
			lead.setPhone(normalizePhone(m.contact().phoneNumber()));
		}
		String text = MessageTexts.describe(m);
		messages.save(new Message(lead, m.messageId(), Direction.IN, text, at, null));
		lead.registerMessage(text, at);
		lead.setLastIncomingAt(at);
		responses.onIncoming(lead, at);
		events.publishEvent(new LeadActivity(lead.getId(), created ? LeadActivity.Kind.LEAD_CREATED : LeadActivity.Kind.MESSAGE_CREATED));
	}

	private void onOutgoingFromApp(TgMessage m) {
		Optional<Lead> found = leads.findByTelegramChatId(m.chat().id());
		if (found.isEmpty()) {
			return; // лидом становится только тот, кто написал сам
		}
		Lead lead = found.get();
		if (messages.existsByLeadAndTelegramMessageId(lead, m.messageId())) {
			return;
		}
		Instant at = m.sentAt();
		String text = MessageTexts.describe(m);
		messages.save(new Message(lead, m.messageId(), Direction.OUT, text, at, null));
		lead.registerMessage(text, at);
		responses.onOutgoing(lead, at, null);
		events.publishEvent(new LeadActivity(lead.getId(), LeadActivity.Kind.MESSAGE_CREATED));
	}

	private void onEdit(TgMessage m) {
		if (m.chat() == null) {
			return;
		}
		leads.findByTelegramChatId(m.chat().id())
			.flatMap(lead -> messages.findByLeadAndTelegramMessageId(lead, m.messageId()))
			.ifPresent(message -> {
				String text = MessageTexts.describe(m);
				message.edit(text, m.editDate() == null ? Instant.now() : Instant.ofEpochSecond(m.editDate()));
				Lead lead = message.getLead();
				messages.findFirstByLeadOrderBySentAtDescIdDesc(lead)
					.filter(latest -> latest.getId().equals(message.getId()))
					.ifPresent(latest -> lead.setLastMessageText(text));
				events.publishEvent(new LeadActivity(lead.getId(), LeadActivity.Kind.MESSAGE_CREATED));
			});
	}

	private void onDelete(TgBusinessMessagesDeleted d) {
		if (d.chat() == null || d.messageIds() == null) {
			return;
		}
		leads.findByTelegramChatId(d.chat().id()).ifPresent(lead -> {
			messages.findByLeadAndTelegramMessageIdIn(lead, d.messageIds()).forEach(Message::markDeleted);
			events.publishEvent(new LeadActivity(lead.getId(), LeadActivity.Kind.MESSAGE_CREATED));
		});
	}

	static String normalizePhone(String phone) {
		if (phone == null || phone.isBlank()) {
			return null;
		}
		String p = phone.trim();
		return p.startsWith("+") ? p : "+" + p;
	}

}
