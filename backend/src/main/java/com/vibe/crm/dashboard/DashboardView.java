package com.vibe.crm.dashboard;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vibe.crm.leads.LeadSource;
import com.vibe.crm.statuses.StatusColor;
import com.vibe.crm.tags.TagColor;

public record DashboardView(LocalDate from, LocalDate to, List<DayCount> newLeadsByDay, List<StatusCount> byStatus,
		List<SourceCount> bySource, List<TagCount> byTag, List<ManagerStats> managers) {

	public record DayCount(LocalDate date, long count) {
	}

	public record StatusCount(Long statusId, String name, StatusColor color, long count) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SourceCount(LeadSource source, long count, int percent) {
	}

	public record TagCount(Long tagId, String name, TagColor color, long count) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ManagerStats(Long managerId, String name, long leads, Long deals, Long avgResponseSeconds) {
	}

}
