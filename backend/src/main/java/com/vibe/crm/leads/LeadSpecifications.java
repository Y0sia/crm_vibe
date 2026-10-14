package com.vibe.crm.leads;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

final class LeadSpecifications {

	private LeadSpecifications() {
	}

	static Specification<Lead> of(LeadFilter f, ZoneId zone) {
		return (root, query, cb) -> {
			List<Predicate> ps = new ArrayList<>();
			if (notBlank(f.q())) {
				Expression<String> name = cb.concat(cb.concat(cb.coalesce(root.get("firstName"), ""), " "),
						cb.concat(cb.concat(cb.coalesce(root.get("lastName"), ""), " "),
								cb.coalesce(root.get("username"), "")));
				ps.add(cb.like(cb.lower(name), pattern(f.q()), '\\'));
			}
			if (notBlank(f.messageText())) {
				ps.add(cb.like(cb.lower(root.get("lastMessageText")), pattern(f.messageText()), '\\'));
			}
			if (f.from() != null) {
				ps.add(cb.greaterThanOrEqualTo(root.get("lastMessageAt"), f.from().atStartOfDay(zone).toInstant()));
			}
			if (f.to() != null) {
				ps.add(cb.lessThan(root.get("lastMessageAt"), f.to().plusDays(1).atStartOfDay(zone).toInstant()));
			}
			if (f.managerNone()) {
				ps.add(cb.isNull(root.get("manager")));
			}
			else if (f.managerId() != null) {
				ps.add(cb.equal(root.get("manager").get("id"), f.managerId()));
			}
			if (f.sourceNone()) {
				ps.add(cb.isNull(root.get("source")));
			}
			else if (f.source() != null) {
				ps.add(cb.equal(root.get("source"), f.source()));
			}
			if (f.statusIds() != null && !f.statusIds().isEmpty()) {
				ps.add(root.get("status").get("id").in(f.statusIds()));
			}
			if (f.tagIds() != null && !f.tagIds().isEmpty()) {
				ps.add(cb.exists(withAnyTag(query.subquery(Long.class), root, cb, f.tagIds())));
			}
			return cb.and(ps.toArray(Predicate[]::new));
		};
	}

	private static Subquery<Long> withAnyTag(Subquery<Long> sq, Root<Lead> root, CriteriaBuilder cb, List<Long> tagIds) {
		Root<Lead> l = sq.from(Lead.class);
		Join<Object, Object> tag = l.join("tags");
		return sq.select(l.get("id")).where(cb.equal(l.get("id"), root.get("id")), tag.get("id").in(tagIds));
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}

	private static String pattern(String s) {
		String escaped = s.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
		return "%" + escaped + "%";
	}

}
