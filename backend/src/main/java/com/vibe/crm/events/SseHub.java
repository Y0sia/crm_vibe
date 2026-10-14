package com.vibe.crm.events;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.vibe.crm.leads.LeadActivity;

/** Рассылает открытым вкладкам CRM сигналы «у лида что-то изменилось». */
@Component
public class SseHub {

	private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

	public SseEmitter subscribe() {
		SseEmitter emitter = new SseEmitter(0L);
		emitters.add(emitter);
		emitter.onCompletion(() -> emitters.remove(emitter));
		emitter.onTimeout(() -> emitters.remove(emitter));
		emitter.onError(e -> emitters.remove(emitter));
		try {
			emitter.send(SseEmitter.event().comment("connected"));
		}
		catch (IOException e) {
			emitters.remove(emitter);
		}
		return emitter;
	}

	public int subscribers() {
		return emitters.size();
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onActivity(LeadActivity activity) {
		broadcast(SseEmitter.event().name("lead").data(activity));
	}

	/** Пинг, чтобы прокси и браузер не закрывали простаивающее соединение. */
	@Scheduled(fixedDelay = 25_000)
	public void heartbeat() {
		broadcast(SseEmitter.event().comment("ping"));
	}

	private void broadcast(SseEmitter.SseEventBuilder event) {
		for (SseEmitter emitter : emitters) {
			try {
				emitter.send(event);
			}
			catch (IOException | IllegalStateException e) {
				emitters.remove(emitter);
			}
		}
	}

}
