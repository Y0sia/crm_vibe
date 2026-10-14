package com.vibe.crm.leads;

import java.time.LocalDate;
import java.util.List;

public record LeadFilter(String q, String messageText, LocalDate from, LocalDate to, Long managerId,
		boolean managerNone, LeadSource source, boolean sourceNone, List<Long> statusIds, List<Long> tagIds) {
}
