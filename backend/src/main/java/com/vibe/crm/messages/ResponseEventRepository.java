package com.vibe.crm.messages;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vibe.crm.leads.Lead;

public interface ResponseEventRepository extends JpaRepository<ResponseEvent, Long> {

	List<ResponseEvent> findByLeadOrderByAnsweredAtAsc(Lead lead);

}
