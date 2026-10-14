package com.vibe.crm;

import java.time.Instant;
import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.leads.Lead;
import com.vibe.crm.leads.LeadEventRepository;
import com.vibe.crm.leads.LeadRepository;
import com.vibe.crm.statuses.Status;
import com.vibe.crm.statuses.StatusColor;
import com.vibe.crm.statuses.StatusRepository;
import com.vibe.crm.tags.Tag;
import com.vibe.crm.tags.TagColor;
import com.vibe.crm.tags.TagRepository;
import com.vibe.crm.users.Role;
import com.vibe.crm.users.User;
import com.vibe.crm.users.UserRepository;

import jakarta.persistence.EntityManager;

/** Подготовка данных для интеграционных тестов. База общая на прогон — каждый тест чистит, что использует. */
@Component
public class TestData {

	private final UserRepository users;

	private final PasswordEncoder encoder;

	private final LeadRepository leads;

	private final LeadEventRepository events;

	private final StatusRepository statuses;

	private final TagRepository tags;

	private final EntityManager em;

	public TestData(UserRepository users, PasswordEncoder encoder, LeadRepository leads, LeadEventRepository events,
			StatusRepository statuses, TagRepository tags, EntityManager em) {
		this.users = users;
		this.encoder = encoder;
		this.leads = leads;
		this.events = events;
		this.statuses = statuses;
		this.tags = tags;
		this.em = em;
	}

	@Transactional
	public User user(String login, String password, Role role) {
		return users.findByLogin(login)
			.orElseGet(() -> users.save(new User(login, encoder.encode(password), login, role)));
	}

	@Transactional
	public Lead lead(long telegramUserId) {
		Status first = statuses.findFirstByOrderByPositionAsc().orElseThrow();
		return leads.save(new Lead(telegramUserId, telegramUserId, first, Instant.now()));
	}

	@Transactional
	public void clearLeads() {
		em.createNativeQuery("delete from response_events").executeUpdate();
		em.createNativeQuery("delete from messages").executeUpdate();
		events.deleteAllInBatch();
		em.createNativeQuery("delete from lead_tags").executeUpdate();
		leads.deleteAllInBatch();
	}

	@Transactional
	public void clearTelegram() {
		clearLeads();
		em.createNativeQuery("delete from business_connections").executeUpdate();
		em.createNativeQuery("delete from telegram_state").executeUpdate();
	}

	/** Пользователей нельзя удалить, пока на них ссылаются лиды и история. */
	@Transactional
	public void clearUsers() {
		clearLeads();
		users.deleteAllInBatch();
	}

	@Transactional
	public void resetStatusesAndTags() {
		clearLeads();
		statuses.deleteAllInBatch();
		tags.deleteAllInBatch();
		List<Object[]> seed = List.of(new Object[] { "Новый", StatusColor.BLUE }, new Object[] { "В работе", StatusColor.ORANGE },
				new Object[] { "Ждёт ответа", StatusColor.PURPLE }, new Object[] { "Квалифицирован", StatusColor.TEAL },
				new Object[] { "Сделка", StatusColor.GREEN }, new Object[] { "Отказ", StatusColor.GRAY });
		for (int i = 0; i < seed.size(); i++) {
			String name = (String) seed.get(i)[0];
			statuses.save(new Status(name, (StatusColor) seed.get(i)[1], i, name.equals("Сделка")));
		}
		for (Object[] t : List.of(new Object[] { "VIP", TagColor.BRICK }, new Object[] { "Опт", TagColor.BLUE },
				new Object[] { "Розница", TagColor.PURPLE }, new Object[] { "Повторный", TagColor.GREEN },
				new Object[] { "Срочно", TagColor.RED })) {
			tags.save(new Tag((String) t[0], (TagColor) t[1]));
		}
	}

}
