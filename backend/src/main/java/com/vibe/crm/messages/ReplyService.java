package com.vibe.crm.messages;

import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.auth.CurrentUser;
import com.vibe.crm.common.ApiException;
import com.vibe.crm.leads.Lead;
import com.vibe.crm.leads.LeadActivity;
import com.vibe.crm.leads.LeadQueryService;
import com.vibe.crm.leads.LeadViews.MessageView;
import com.vibe.crm.telegram.BusinessConnection;
import com.vibe.crm.telegram.BusinessConnectionRepository;
import com.vibe.crm.telegram.TelegramApiException;
import com.vibe.crm.telegram.TelegramBotClient;
import com.vibe.crm.telegram.api.TgMessage;
import com.vibe.crm.users.UserRepository;

/** Ответ лиду из CRM — отправляется в Telegram от имени аккаунта компании. */
@Service
@Transactional
public class ReplyService {

	private final LeadQueryService leads;

	private final BusinessConnectionRepository connections;

	private final TelegramBotClient telegram;

	private final MessageRepository messages;

	private final ResponseTracker responses;

	private final UserRepository users;

	private final ApplicationEventPublisher events;

	public ReplyService(LeadQueryService leads, BusinessConnectionRepository connections, TelegramBotClient telegram,
			MessageRepository messages, ResponseTracker responses, UserRepository users,
			ApplicationEventPublisher events) {
		this.leads = leads;
		this.connections = connections;
		this.telegram = telegram;
		this.messages = messages;
		this.responses = responses;
		this.users = users;
		this.events = events;
	}

	public MessageView reply(Long leadId, String text) {
		Lead lead = leads.get(leadId);
		BusinessConnection connection = lead.getBusinessConnectionId() == null ? null
				: connections.findById(lead.getBusinessConnectionId()).orElse(null);
		String reason = ReplyAvailability.reason(lead, connection, Instant.now());
		if (reason != null) {
			throw ApiException.conflict(reason);
		}
		TgMessage sent;
		try {
			sent = telegram.sendMessage(connection.getId(), lead.getTelegramChatId(), text.trim());
		}
		catch (TelegramApiException e) {
			throw new ApiException(HttpStatus.BAD_GATEWAY, "Telegram не принял сообщение: " + e.description());
		}
		Long authorId = CurrentUser.get().id();
		Instant at = sent.sentAt();
		Message message = messages.save(new Message(lead, sent.messageId(), Direction.OUT, text.trim(), at,
				users.getReferenceById(authorId)));
		lead.registerMessage(message.getText(), at);
		responses.onOutgoing(lead, at, authorId);
		events.publishEvent(new LeadActivity(lead.getId(), LeadActivity.Kind.MESSAGE_CREATED));
		return MessageView.of(message);
	}

}
