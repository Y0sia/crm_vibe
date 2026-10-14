package com.vibe.crm.statuses;

public record StatusDto(Long id, String name, StatusColor color, int position, boolean won, long leadCount) {

	static StatusDto of(Status s, long leadCount) {
		return new StatusDto(s.getId(), s.getName(), s.getColor(), s.getPosition(), s.isWon(), leadCount);
	}

}
