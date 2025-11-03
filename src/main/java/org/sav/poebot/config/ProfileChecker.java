package org.sav.poebot.config;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProfileChecker {

	private final Environment environment;

	public boolean isProd() {
		return isExists("Prod");
	}

	public boolean isDev() {
		return isExists("dev");
	}

	private boolean isExists(String param) {
		for (String profile : environment.getActiveProfiles()) {
			if (param.equalsIgnoreCase(profile)) {
				return true;
			}
		}
		return false;
	}
}
