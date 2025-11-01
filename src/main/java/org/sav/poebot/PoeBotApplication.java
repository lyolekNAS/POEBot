package org.sav.poebot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Slf4j
public class PoeBotApplication {

	public static void main(String[] args) {
		log.info("--------------------------------------------<STARTING>--------------------------------------------");
		SpringApplication.run(PoeBotApplication.class, args);
		log.info("--------------------------------------------<STARTED>--------------------------------------------");
	}

}
