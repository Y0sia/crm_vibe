package com.vibe.crm.tags;

public record TagDto(Long id, String name, TagColor color, long leadCount) {

	static TagDto of(Tag t, long leadCount) {
		return new TagDto(t.getId(), t.getName(), t.getColor(), leadCount);
	}

}
