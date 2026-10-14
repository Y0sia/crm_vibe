package com.vibe.crm.leads;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import com.vibe.crm.statuses.Status;
import com.vibe.crm.tags.Tag;

public interface LeadRepository extends JpaRepository<Lead, Long>, JpaSpecificationExecutor<Lead> {

	Optional<Lead> findByTelegramUserId(long telegramUserId);

	Optional<Lead> findByTelegramChatId(long telegramChatId);

	List<Lead> findByStatus(Status status);

	List<Lead> findByTagsContains(Tag tag);

	long countByStatus(Status status);

	/** [statusId, count] по всем лидам. */
	@Query("select l.status.id, count(l) from Lead l group by l.status.id")
	List<Object[]> countByStatus();

	/** [tagId, count] по всем лидам. */
	@Query("select t.id, count(l) from Lead l join l.tags t group by t.id")
	List<Object[]> countByTag();

}
