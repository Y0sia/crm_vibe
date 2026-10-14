package com.vibe.crm.telegram;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.vibe.crm.telegram.api.TgUpdate;

/** Фоновый long polling getUpdates. Offset хранится в БД, чтобы не терять и не повторять апдейты. */
@Component
public class UpdatePoller implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(UpdatePoller.class);

	static final int TIMEOUT_SECONDS = 25;

	private final TelegramBotClient telegram;

	private final UpdateProcessor processor;

	private final TelegramStateRepository state;

	private final TelegramProperties props;

	private final TransactionTemplate tx;

	private volatile Thread thread;

	private volatile boolean running;

	public UpdatePoller(TelegramBotClient telegram, UpdateProcessor processor, TelegramStateRepository state,
			TelegramProperties props, TransactionTemplate tx) {
		this.telegram = telegram;
		this.processor = processor;
		this.state = state;
		this.props = props;
		this.tx = tx;
	}

	/** Один запрос к Telegram и обработка полученной пачки. */
	public int pollOnce() {
		List<TgUpdate> updates = telegram.getUpdates(offset(), TIMEOUT_SECONDS);
		for (TgUpdate update : updates) {
			try {
				processor.process(update);
			}
			catch (RuntimeException e) {
				log.error("Не удалось обработать апдейт {}", update.updateId(), e);
			}
			saveOffset(update.updateId() + 1);
		}
		return updates.size();
	}

	private long offset() {
		return state.findById(TelegramState.OFFSET).map(s -> Long.parseLong(s.getValue())).orElse(0L);
	}

	private void saveOffset(long offset) {
		tx.executeWithoutResult(t -> {
			TelegramState s = state.findById(TelegramState.OFFSET)
				.orElseGet(() -> new TelegramState(TelegramState.OFFSET, "0"));
			s.setValue(Long.toString(offset));
			state.save(s);
		});
	}

	private void loop() {
		Duration backoff = Duration.ofSeconds(1);
		while (running) {
			try {
				pollOnce();
				backoff = Duration.ofSeconds(1);
			}
			catch (TelegramApiException e) {
				if (e.errorCode() == 401 || e.errorCode() == 404) {
					log.error("Telegram отклонил токен бота ({}). Приём сообщений остановлен — проверьте TELEGRAM_BOT_TOKEN.",
							e.description());
					running = false;
					return;
				}
				log.warn("Ошибка Telegram: {}. Повтор через {} с", e.getMessage(), backoff.toSeconds());
				backoff = sleep(backoff);
			}
			catch (RuntimeException e) {
				log.warn("Ошибка приёма апдейтов: {}. Повтор через {} с", e.toString(), backoff.toSeconds());
				backoff = sleep(backoff);
			}
		}
	}

	private Duration sleep(Duration backoff) {
		try {
			Thread.sleep(backoff);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			running = false;
		}
		Duration next = backoff.multipliedBy(2);
		return next.compareTo(Duration.ofSeconds(60)) > 0 ? Duration.ofSeconds(60) : next;
	}

	@Override
	public void start() {
		if (!props.pollingEnabled()) {
			log.info("Приём сообщений Telegram выключен (crm.telegram.polling-enabled=false)");
			return;
		}
		if (!props.configured()) {
			log.warn("TELEGRAM_BOT_TOKEN не задан — приём сообщений из Telegram не запущен");
			return;
		}
		running = true;
		thread = Thread.ofVirtual().name("telegram-poller").start(this::loop);
		log.info("Приём сообщений Telegram запущен");
	}

	@Override
	public void stop() {
		running = false;
		Thread t = thread;
		if (t != null) {
			t.interrupt();
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

}
