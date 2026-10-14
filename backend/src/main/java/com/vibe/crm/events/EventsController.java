package com.vibe.crm.events;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class EventsController {

	private final SseHub hub;

	public EventsController(SseHub hub) {
		this.hub = hub;
	}

	@GetMapping(path = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter events() {
		return hub.subscribe();
	}

}
