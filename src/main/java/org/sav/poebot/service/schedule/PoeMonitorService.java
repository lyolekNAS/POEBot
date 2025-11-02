package org.sav.poebot.service.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.select.Elements;
import org.sav.poebot.model.*;
import org.sav.poebot.service.TelegramNotifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class PoeMonitorService {
	private static final ObjectMapper mapper = new ObjectMapper();

	private static final String URL = "https://www.poe.pl.ua/customs/newgpv-info.php";
	private static final File DATA_DIR = new File("data");

	private final TelegramNotifier telegram;

	@Scheduled(fixedRate = 60_000) // кожну хвилину
	public void checkSchedule() {
		String date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
		try {
			ScheduleResponse newData = fetchAndParse(date);
			String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(newData);

			if (!DATA_DIR.exists()) DATA_DIR.mkdirs();
			File file = new File(DATA_DIR, "gpv_" + date + ".json");

			ScheduleResponse oldData = null;
			if (file.exists()) {
				String oldJson = Files.readString(file.toPath());
				oldData = mapper.readValue(oldJson, ScheduleResponse.class);
			}

			if (!file.exists() || !Objects.equals(newData, oldData)) {
				Files.writeString(file.toPath(), json);
				log.info("🔄 Зміни виявлено для {}, файл оновлено", date);
				notifyIfChanged(newData, oldData);
			} else {
				log.info("Без змін ({})", date);
			}

		} catch (Exception e) {
			log.error("❌ Помилка при перевірці графіка: {}", e.getMessage(), e);
		}
	}

	private ScheduleResponse fetchAndParse(String date) throws Exception {
		String payload = "seldate=" + URLEncoder.encode("{\"date_in\":\"" + date + "\"}", StandardCharsets.UTF_8);
		Document doc = Jsoup.connect(URL)
				.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
				.requestBody(payload)
				.method(org.jsoup.Connection.Method.POST)
				.timeout(15_000)
				.post();

		// 1. Загальна інформація
		String infoText = doc.select("div.gpvinfodetail").text();
		Pattern p = Pattern.compile("з (\\d{2}:\\d{2}) по (\\d{2}:\\d{2}).+?обсязі (\\d+(?:\\.\\d+)?)");
		Matcher m = p.matcher(infoText);
		List<InfoBlock> info = new ArrayList<>();
		while (m.find()) {
			info.add(new InfoBlock(m.group(1), m.group(2), Double.parseDouble(m.group(3))));
		}

		// 2. Таблиця
		List<QueueData> schedule = new ArrayList<>();
		Elements rows = doc.select("table.turnoff-scheduleui-table tbody tr");
		QueueData currentQueue = null;

		for (Element row : rows) {
			Elements tds = row.select("td");
			if (tds.isEmpty()) continue;
			boolean isMain = tds.get(0).hasClass("turnoff-scheduleui-table-queue");

			if (isMain) {
				int queueNum = Integer.parseInt(tds.get(0).text().replaceAll("\\D", ""));
				currentQueue = new QueueData(queueNum, new ArrayList<>());
				int subQueue = Integer.parseInt(tds.get(1).text().trim());
				currentQueue.subqueues().add(new SubQueue(subQueue, extractLights(tds.subList(2, tds.size()))));
			} else if (currentQueue != null) {
				int subQueue = Integer.parseInt(tds.get(0).text().trim());
				currentQueue.subqueues().add(new SubQueue(subQueue, extractLights(tds.subList(1, tds.size()))));
				if (subQueue == 2) {
					schedule.add(currentQueue);
					currentQueue = null;
				}
			}
		}

		return new ScheduleResponse(date, info, schedule);
	}

	private List<String> extractLights(List<Element> elements) {
		List<String> list = new ArrayList<>();
		for (Element td : elements) {
			String cls = td.className();
			if (cls == null || cls.isEmpty()) cls = "light_0";
			list.add(cls);
		}
		return list;
	}

	private void notifyIfChanged(ScheduleResponse newData, ScheduleResponse oldData) {
		for (QueueData q : newData.schedule()) {
			for (SubQueue sq : q.subqueues()) {
				String key = q.queue() + "." + sq.subqueue();
				List<String> newHours = sq.hours();
				List<String> oldHours = findOldHours(oldData, q.queue(), sq.subqueue());

				if (!newHours.equals(oldHours)) {
					String msg = buildMessage(q.queue(), sq.subqueue(), newHours);
					telegram.sendMessage(key, msg);
				}
			}
		}
	}

	private List<String> findOldHours(ScheduleResponse oldData, int queue, int subqueue) {
		if (oldData == null) return List.of();
		return oldData.schedule().stream()
				.filter(q -> q.queue() == queue)
				.flatMap(q -> q.subqueues().stream())
				.filter(sq -> sq.subqueue() == subqueue)
				.map(SubQueue::hours)
				.findFirst()
				.orElse(List.of());
	}

	private String buildMessage(int queue, int subqueue, List<String> hours) {
		StringBuilder sb = new StringBuilder();
		sb.append("Черга ").append(queue).append(".").append(subqueue).append("\n");

		String currentStatus = getStatus(hours.get(0));
		int startMin = 0;

		for (int h = 1; h <= hours.size(); h++) {
			String nextStatus = (h < hours.size()) ? getStatus(hours.get(h)) : null;

			if (nextStatus == null || !nextStatus.equals(currentStatus)) {
				int endMin = h * 30;
				String from = String.format("%02d:%02d", startMin / 60, startMin % 60);
				String to = String.format("%02d:%02d", endMin / 60, endMin % 60);

				sb.append(from).append(" - ").append(to).append(" - ").append(currentStatus).append("\n");

				if (nextStatus != null) {
					currentStatus = nextStatus;
					startMin = endMin;
				}
			}
		}

		return sb.toString().trim();
	}

	private String getStatus(String cls) {
		return switch (cls) {
			case "light_2" -> "\uD83D\uDD34";
			case "light_3" -> "\uD83D\uDFE1";
			default -> "\uD83D\uDFE2";
		};
	}
}
