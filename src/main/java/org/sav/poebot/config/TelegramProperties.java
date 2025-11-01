package org.sav.poebot.config;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConfigurationProperties(prefix = "telegram")
@ToString
@Getter
@Setter
public class TelegramProperties {
	private String botToken;
	private Map<String, Long> channels;
}

