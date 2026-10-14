package com.vibe.crm.leads;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.IntegrationTest;
import com.vibe.crm.TestData;
import com.vibe.crm.statuses.Status;
import com.vibe.crm.statuses.StatusRepository;
import com.vibe.crm.tags.Tag;
import com.vibe.crm.tags.TagRepository;

@IntegrationTest
@Transactional
class LeadStorageTest {

	@Autowired
	TestData data;

	@Autowired
	StatusRepository statuses;

	@Autowired
	TagRepository tags;

	@Autowired
	LeadRepository leads;

	@Autowired
	LeadHistory history;

	@Autowired
	LeadEventRepository events;

	@BeforeEach
	void clean() {
		data.resetStatusesAndTags();
	}

	@Test
	void seedStatusesAndTagsExist() {
		assertThat(statuses.findAllByOrderByPositionAsc()).extracting(Status::getName)
			.containsExactly("Новый", "В работе", "Ждёт ответа", "Квалифицирован", "Сделка", "Отказ");
		assertThat(statuses.findAllByOrderByPositionAsc()).filteredOn(Status::isWon)
			.extracting(Status::getName).containsExactly("Сделка");
		assertThat(tags.findAllByOrderByIdAsc()).extracting(Tag::getName)
			.containsExactly("VIP", "Опт", "Розница", "Повторный", "Срочно");
	}

	@Test
	void leadWithTagsAndHistoryIsStored() {
		Status first = statuses.findFirstByOrderByPositionAsc().orElseThrow();
		Tag vip = tags.findAllByOrderByIdAsc().getFirst();
		Lead lead = new Lead(548213097L, 548213097L, first, Instant.parse("2026-10-13T12:47:00Z"));
		lead.setFirstName("Дмитрий");
		lead.setLastName("Орлов");
		lead.setUsername("orlov_d");
		lead.getTags().add(vip);
		leads.save(lead);

		history.record(lead, null, LeadEventType.CREATED, null, null);

		Lead found = leads.findByTelegramUserId(548213097L).orElseThrow();
		assertThat(found.displayName()).isEqualTo("Дмитрий Орлов");
		assertThat(found.getTags()).containsExactly(vip);
		assertThat(leads.countByStatus(first)).isEqualTo(1);
		assertThat(events.findByLeadOrderByCreatedAtDescIdDesc(found)).extracting(LeadEvent::getType)
			.containsExactly(LeadEventType.CREATED);
	}

	@Test
	void displayNameFallsBackToUsernameThenId() {
		Status first = statuses.findFirstByOrderByPositionAsc().orElseThrow();
		Lead lead = new Lead(1L, 1L, first, Instant.now());
		assertThat(lead.displayName()).isEqualTo("ID 1");
		lead.setUsername("anna_sm");
		assertThat(lead.displayName()).isEqualTo("@anna_sm");
	}

}
