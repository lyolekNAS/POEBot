package org.sav.poebot.service;

import org.sav.poebot.config.TelegramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;


@Component
public class TelegramNotifier {
	private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);
	private final RestTemplate restTemplate = new RestTemplate();
	private final TelegramProperties props;

	public TelegramNotifier(TelegramProperties props) {
		this.props = props;
	}

	public void sendMessage(String queueKey, String message) {
		Long chatId = props.getChannels().get(queueKey);
		if (chatId == null) {
			log.warn("Немає chat_id для {}", queueKey);
			return;
		}

		String url = String.format("https://api.telegram.org/bot%s/sendMessage", props.getBotToken());

		Map<String, Object> payload = Map.of(
				"chat_id", chatId,
				"text", message,
				"parse_mode", "HTML"
		);

		try {
			restTemplate.postForObject(url, payload, String.class);
			log.info("📨 Відправлено в канал {}: {}", queueKey, message.split("\n")[0]);
		} catch (Exception e) {
			log.error("Помилка надсилання в Telegram: {}", e.getMessage());
		}
	}
}

