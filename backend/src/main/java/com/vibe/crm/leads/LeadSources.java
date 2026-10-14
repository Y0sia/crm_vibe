package com.vibe.crm.leads;

public final class LeadSources {

	private LeadSources() {
	}

	public static String label(LeadSource source) {
		if (source == null) {
			return null;
		}
		return switch (source) {
			case CHANNEL -> "Канал";
			case GROUP -> "Группа";
			case ADS -> "Реклама";
			case REFERRAL -> "Сарафан";
			case OTHER -> "Другое";
		};
	}

}
