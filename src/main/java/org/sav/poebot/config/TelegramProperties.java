package org.sav.poebot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConfigurationProperties(prefix = "telegram")
public class TelegramProperties {
	private String botToken;
	private Map<String, Long> channels;

	public String getBotToken() {
		return botToken;
	}

	public void setBotToken(String botToken) {
		this.botToken = botToken;
	}

	public Map<String, Long> getChannels() {
		return channels;
	}

	public void setChannels(Map<String, Long> channels) {
		this.channels = channels;
	}
}

