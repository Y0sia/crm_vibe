package com.vibe.crm.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vibe.crm.common.ApiException;
import com.vibe.crm.dashboard.DashboardView.DayCount;
import com.vibe.crm.dashboard.DashboardView.ManagerStats;
import com.vibe.crm.dashboard.DashboardView.SourceCount;
import com.vibe.crm.dashboard.DashboardView.StatusCount;
import com.vibe.crm.dashboard.DashboardView.TagCount;
import com.vibe.crm.leads.LeadSource;
import com.vibe.crm.statuses.Status;
import com.vibe.crm.statuses.StatusRepository;
import com.vibe.crm.tags.TagRepository;
import com.vibe.crm.users.User;
import com.vibe.crm.users.UserRepository;

import jakarta.persistence.EntityManager;

@Service
@Transactional(readOnly = true)
public class DashboardService {

	private final EntityManager em;

	private final StatusRepository statuses;

	private final TagRepository tags;

	private final UserRepository users;

	private final ZoneId zone;

	public DashboardService(EntityManager em, StatusRepository statuses, TagRepository tags, UserRepository users,
			@Value("${crm.timezone:Europe/Moscow}") String timezone) {
		this.em = em;
		this.statuses = statuses;
		this.tags = tags;
		this.users = users;
		this.zone = ZoneId.of(timezone);
	}

	public LocalDate today() {
		return LocalDate.now(zone);
	}

	public DashboardView build(LocalDate from, LocalDate to) {
		if (from.isAfter(to)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Дата «с» позже даты «по»");
		}
		if (ChronoUnit.DAYS.between(from, to) > 366) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Период не может быть длиннее года");
		}
		Instant start = from.atStartOfDay(zone).toInstant();
		Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
		return new DashboardView(from, to, byDay(from, to, start, end), byStatus(start, end), bySource(start, end),
				byTag(start, end), managers(start, end));
	}

	@SuppressWarnings("unchecked")
	private List<Object[]> rows(String sql, Instant start, Instant end) {
		return em.createNativeQuery(sql).setParameter("start", start).setParameter("end", end).getResultList();
	}

	private static long num(Object o) {
		return o == null ? 0 : ((Number) o).longValue();
	}

	private List<DayCount> byDay(LocalDate from, LocalDate to, Instant start, Instant end) {
		Map<LocalDate, Long> counts = new HashMap<>();
		for (Object[] r : rows("select cast(created_at at time zone '" + zone.getId() + "' as date), count(*) from leads"
				+ " where created_at >= :start and created_at < :end group by 1", start, end)) {
			LocalDate day = r[0] instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) r[0];
			counts.put(day, num(r[1]));
		}
		List<DayCount> days = new ArrayList<>();
		for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
			days.add(new DayCount(d, counts.getOrDefault(d, 0L)));
		}
		return days;
	}

	private List<StatusCount> byStatus(Instant start, Instant end) {
		Map<Long, Long> counts = new HashMap<>();
		for (Object[] r : rows("select status_id, count(*) from leads where created_at >= :start and created_at < :end"
				+ " group by status_id", start, end)) {
			counts.put(num(r[0]), num(r[1]));
		}
		return statuses.findAllByOrderByPositionAsc().stream()
			.map(s -> new StatusCount(s.getId(), s.getName(), s.getColor(), counts.getOrDefault(s.getId(), 0L)))
			.toList();
	}

	private List<SourceCount> bySource(Instant start, Instant end) {
		Map<LeadSource, Long> counts = new HashMap<>();
		long total = 0;
		for (Object[] r : rows("select source, count(*) from leads where created_at >= :start and created_at < :end"
				+ " group by source", start, end)) {
			LeadSource source = r[0] == null ? null : LeadSource.valueOf((String) r[0]);
			counts.put(source, num(r[1]));
			total += num(r[1]);
		}
		List<SourceCount> result = new ArrayList<>();
		List<LeadSource> order = new ArrayList<>(List.of(LeadSource.values()));
		order.add(null);
		for (LeadSource s : order) {
			long c = counts.getOrDefault(s, 0L);
			if (s == null && c == 0) {
				continue;
			}
			int percent = total == 0 ? 0 : (int) Math.round(c * 100.0 / total);
			result.add(new SourceCount(s, c, percent));
		}
		result.sort((a, b) -> Long.compare(b.count(), a.count()));
		return result;
	}

	private List<TagCount> byTag(Instant start, Instant end) {
		Map<Long, Long> counts = new HashMap<>();
		for (Object[] r : rows("select lt.tag_id, count(*) from lead_tags lt join leads l on l.id = lt.lead_id"
				+ " where l.created_at >= :start and l.created_at < :end group by lt.tag_id", start, end)) {
			counts.put(num(r[0]), num(r[1]));
		}
		return tags.findAllByOrderByIdAsc().stream()
			.map(t -> new TagCount(t.getId(), t.getName(), t.getColor(), counts.getOrDefault(t.getId(), 0L)))
			.toList();
	}

	private List<ManagerStats> managers(Instant start, Instant end) {
		Long wonId = statuses.findAllByOrderByPositionAsc().stream().filter(Status::isWon).map(Status::getId)
			.findFirst().orElse(null);
		Map<Long, long[]> leadStats = new HashMap<>();
		for (Object[] r : rows("select manager_id, count(*), count(*) filter (where status_id = "
				+ (wonId == null ? "-1" : wonId) + ") from leads where created_at >= :start and created_at < :end"
				+ " group by manager_id", start, end)) {
			leadStats.put(r[0] == null ? null : num(r[0]), new long[] { num(r[1]), num(r[2]) });
		}
		Map<Long, Long> avg = new HashMap<>();
		for (Object[] r : rows("select author_id, avg(waited_seconds) from response_events where author_id is not null"
				+ " and answered_at >= :start and answered_at < :end group by author_id", start, end)) {
			avg.put(num(r[0]), Math.round(((Number) r[1]).doubleValue()));
		}
		List<ManagerStats> result = new ArrayList<>();
		for (User u : users.findByActiveTrueOrderByDisplayNameAsc()) {
			long[] s = leadStats.getOrDefault(u.getId(), new long[] { 0, 0 });
			result.add(new ManagerStats(u.getId(), u.getDisplayName(), s[0], wonId == null ? null : s[1],
					avg.get(u.getId())));
		}
		long[] none = leadStats.getOrDefault(null, new long[] { 0, 0 });
		result.add(new ManagerStats(null, "Не назначен", none[0], null, null));
		return result;
	}

}
