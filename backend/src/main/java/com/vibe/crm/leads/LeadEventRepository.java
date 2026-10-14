package com.vibe.crm.leads;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadEventRepository extends JpaRepository<LeadEvent, Long> {

	List<LeadEvent> findByLeadOrderByCreatedAtDescIdDesc(Lead lead);

}
